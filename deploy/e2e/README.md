# Teste de ponta a ponta

Um roteiro para ir do **banco limpo** ao **rollback**, passando por login, usuários, permissões, cadastros, a esteira do GitLab e a
auditoria. O que é exercitado de verdade: Keycloak (login), a API, o GitLab da POC (`deploy/gitlab-poc`), a CLI `lqm` dentro do job,
PostgreSQL como banco alvo e o runner de rollback.

## 1. Subir do zero

Precisa do GitLab da POC no ar (`docker compose -f deploy/gitlab-poc/docker-compose.yml up -d`) e do Docker.

```bash
python deploy/e2e/e2e.py reset
```

**Apaga** o banco do LiquiManager e os dados do Keycloak (os volumes do compose); o GitLab não é tocado. Sobe Postgres, a API
(com login ligado), o Keycloak, cria os bancos alvo (`pocdev`, `poctst`, `pochml`, `pocprd`, usuário `poc`/`poc`) e põe no GitLab o job
`lqm-deploy`, que aplica o que uma promoção pede.

```bash
cd lqm-web && npm run dev:oidc
```

Abra **http://localhost:5173**. Se outro `vite` já estiver na 5173, o novo cai em outra porta e o login falha: o realm só aceita 5173 e
5180. Pare o outro antes. (`npm run dev`, sem `:oidc`, é o que **não** pede login.)

## 2. Entrar, e criar usuários

Entre como **`admin.lqm`** / `lqm-dev-2026` (administrador de bootstrap: todas as permissões).

Os usuários nascem no Keycloak, em **http://localhost:8081** (`admin` / `admin`) → realm `liquimanager` → *Users* → *Add user*. Crie, com
senha definitiva (*Credentials*, *Temporary* desligado) e no grupo indicado (*Groups* → *Join Group*):

| Usuário | Grupo | Para quê |
|---------|-------|----------|
| `ana.dev` | `developers` | Quem escreve a migration |
| `rita.release` | `developers` | Quem promove para HML |
| `dba.demo` | `dbas` | Quem aprova e faz rollback (já existe no realm) |

O LiquiManager **não lista** os usuários do Keycloak: uma pessoa só aparece em Acessos e Permissões depois de **entrar uma vez** (janela
anônima), quando vira *principal* sozinha, **sem permissão alguma**. Alternativa: em *Conceder acesso*, *Cadastrar principal* com o login exato
do Keycloak (`ana.dev`) como identificador. É o ponto de partida da próxima etapa.

## 3. Permissões

Como `admin.lqm`: **Administrar → Acessos e Permissões**. Ligue cada pessoa (ou o grupo) a um papel:

| Quem | Papel | Escopo |
|------|-------|--------|
| grupo `developers` | Desenvolvedor | workspace |
| `rita.release` | Release Manager | workspace |
| grupo `dbas` | DBA | workspace |

Confirme a diferença: antes do binding `ana.dev` entra e não consegue ler os cadastros; depois enxerga as telas, mas não pode promover (o papel
Desenvolvedor só faz `deploy` em DEV e TST). O que cada pessoa pode está em **Configurações → Permissões**.

## 4. Cadastros (Administrar)

1. **Projetos** → *Novo projeto*: slug `liquibase-poc`, repositório `http://host.docker.internal:8929/root/liquibase-poc.git`, branch `main`.
   Abra o projeto → aba **Changelogs** → *Adicionar*: nome `master`, caminho `db/changelog/db.changelog-master.yaml`, formato `YAML`.
2. **Conexões** → *Nova conexão*, uma por ambiente. Nenhuma senha vai para o LiquiManager: só a referência.

   | Slug | Ambiente | Engine | JDBC URL | Segredo |
   |------|----------|--------|----------|---------|
   | `poc-dev` | DEV | PostgreSQL | `jdbc:postgresql://postgres:5432/pocdev` | provedor `ENV`, referência `LQM_E2E_DB` |
   | `poc-hml` | HML | PostgreSQL | `jdbc:postgresql://postgres:5432/pochml` | idem |

   Deixe o scanner desligado (ou ligue e veja o drift detectar o que o runner fez por fora). *Testar conexão* tem que dar ok.
3. Volte ao projeto → aba **Conexões** → *Vincular conexões* → marque as duas → *Salvar vínculos*.
4. **Pipelines** → *Novo pipeline*: nome `POC-GATE`, projeto `liquibase-poc`, GitLab project id `1`, ref `main`. Estágios: 1 = DEV
   (`poc-dev`), 2 = HML (`poc-hml`). Marque **exige aprovação** no HML se quiser exercitar a segregação de funções (quem pede não aprova).
5. **Ambientes**: arraste para mudar a ordem, se quiser ver a ordem dos pipelines mudar.

## 5. Tokens e a ligação com o GitLab

**Tokens** → *Emitir*, duas vezes (o valor aparece uma única vez):

```bash
# 1) escopos ingest + checks:read: é o que o job do GitLab usa para falar com o LiquiManager
python deploy/gitlab-poc/connect-lqm.py ci --token lqm_...

# 2) escopos runner + ingest: é o que o runner de rollback usa
python deploy/e2e/e2e.py runner lqm_...
```

**Não pule o primeiro:** sem um token válido o job ainda aplica a migration (o LiquiManager é fail-open, ADR-004), mas **nada é
reportado** e o dashboard fica vazio; no log do job aparece `ingest answered 401`.

O primeiro grava `LQM_URL` e `LQM_TOKEN` como variáveis do projeto no GitLab. O segundo sobe o runner (contêiner `liquimanager-runner-1`) na
rede do compose, para resolver `postgres:5432`.

## 6. A esteira: merge, DEV, promoção para HML

**A regra: o que está em HML tem de estar antes em DEV.** O `lqm update` confere, antes de aplicar, se os changesets estão executados no
estágio anterior do pipeline; se não estão, para com código 3 e o HML não é tocado (D-22). Por isso a ordem importa.

O projeto no GitLab (`http://localhost:8929/root/liquibase-poc`) começa assim: a `main` tem o changeset **001**, e o **002** espera num merge
request aberto. DEV e HML estão vazios.

1. **Tente promover antes de tudo** (como `rita.release`: **Automatizar → POC-GATE → Matriz**, coluna **HML**, botão **Promover**, release
   *Sem release*). O pipeline do GitLab roda, mas o job `lqm-deploy` **falha**: `[BLOCK] order  not in DEV yet: 001-create-cliente`. A
   operação aparece como *bloqueada* em Operações e o banco `pochml` continua vazio.
2. **Faça o merge do MR** no GitLab. O merge na `main` dispara o job `lqm-deploy-dev`, que aplica **001 e 002** em `pocdev` e reporta ao
   LiquiManager. Veja o pipeline em `.../-/pipelines` e a operação em Operações; a coluna DEV da matriz passa a mostrar os dois changesets.
3. **Promova para HML de novo.** Agora o job `lqm-deploy` roda `lqm update --tag release` em `pochml`, a conferência passa
   (`[PASS] order  everything to apply is already in DEV`) e os dois changesets são aplicados.
4. Com **Solicitar aprovação automaticamente** ligado no estágio HML (Pipelines → estágio → editar), o merge do passo 2 já abre o pedido de
   promoção para HML, em nome de `system`: ele aparece em *Promoções aguardando aprovação*, sem ninguém apertar Promover.
5. Com aprovação exigida no estágio HML: o pedido fica *aguardando aprovação* e só dispara depois que **outra** pessoa com `promote:hml`
   aprova (quem pediu não pode; o papel DBA não tem `promote`, então aprove como `admin.lqm`, ou dê Release Manager a um segundo usuário).

Conferir nos bancos: `docker exec lqm-postgres psql -U poc -d pocdev -c "\dt"` (e `pochml`).

**Para repetir do começo** (bancos alvo vazios, operações do LiquiManager apagadas, git de volta a "001 na main, 002 no MR"), mantendo
usuários, cadastros, tokens e a trilha de auditoria:

```bash
python deploy/e2e/e2e.py again
```

## 7. Rollback

Como `dba.demo` (ou quem tiver `rollback:hml`): abra a operação → painel **Rollbacks** → *Solicitar rollback* do changeset
`001-create-cliente`, com o motivo. Fora de produção o pedido já nasce aprovado; em produção espera um segundo aprovador.

Em até 5 segundos o runner pega o pedido, clona o repositório **no commit da operação original**, reverte e reporta. O pedido passa a
*concluído* e a tabela `cliente` some de `pochml`. `docker logs liquimanager-runner-1` mostra o que ele fez.

## 8. Fechando

**Auditoria**: cada passo acima está lá (concessões, cadastros, promoção, aprovação, rollback), com a cadeia de hashes verificada.
*Exportar CSV* e *Exportar PDF* registram o SHA-256 do arquivo no próprio log.

## Para testar à mão, sem login

```bash
python deploy/e2e/e2e.py reset --dev
python deploy/e2e/e2e.py prepare
cd lqm-web && npm run dev      # ou a imagem web, em http://localhost:8088
```

`prepare` faz o que os passos 4 e 5 fazem pela tela: projeto `liquibase-poc` com o changelog, conexões `poc-dev` e `poc-hml`, pipeline `POC-GATE`, os dois tokens (o do job fica no GitLab, o do runner vai para o contêiner `liquimanager-runner-1`) e o MR com o changeset 002. Depois é só: merge do MR no GitLab (aplica 001 e 002 em DEV), **Promover** para HML na matriz e *Solicitar rollback* na operação.

## Conferência automática

O mesmo caminho, sem a parte do login e feito por script, roda com:

```bash
python deploy/e2e/e2e.py reset --dev   # cadeia de desenvolvimento: sem login
python deploy/e2e/e2e.py check         # cadastra, promove, espera o pipeline, faz o rollback e confere o banco
python deploy/e2e/e2e.py reset         # volta para o banco limpo, com login
```

O `check` espera o repositório da POC no ponto de partida (001 na `main`, 002 no MR) e um banco do LiquiManager limpo. Se o GitLab já recebeu merges, rode `python deploy/e2e/e2e.py again` antes; para repetir o `check`, rode `reset --dev` de novo (ele cadastra tudo e responde 409 em cadastros que já existem). Subir o serviço `web` do compose junto exige os mesmos `-f` do `e2e.py` (senão a API é recriada sem o OIDC).
