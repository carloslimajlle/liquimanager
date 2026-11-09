# 07 — Coletores e integração

Três formas de levar operações ao LiquiManager. Todas usam `lqm-core-client` (HTTP + buffer) e `lqm-policy-engine`.

| Coletor | Quando usar | Captura | Pode bloquear? |
|---------|-------------|---------|----------------|
| **Spring Boot starter** | Serviços que rodam Liquibase no startup (situação atual) | Tudo (changesets, SQL, erro, contexto) | Sim (`lqm.enforce=true`) |
| **CLI `lqm`** | Pipeline GitLab, execução local, runner de rollback | Tudo | Sim (exit code) |
| **Scanner agentless** | Bancos sem coletor / detectar execuções por fora | Só o que está na `DATABASECHANGELOG` | Não (só detecta) |

---

## 1. Spring Boot starter

### 1.1 Uso no serviço

```xml
<dependency>
  <groupId>io.github.carloslimajlle.liquimanager</groupId>
  <artifactId>lqm-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

```yaml
spring:
  liquibase:
    change-log: classpath:db/changelog/db.changelog-master.xml
    enabled: true
    contexts: ${spring.profiles.active}
lqm:
  url: https://lqm.<dominio>/api/v1        # obrigatório
  token: ${LQM_TOKEN}                      # token de serviço (escopo ingest), via secret do k8s
  project: auditoria                       # slug do projeto
  connection-key: core-${spring.profiles.active}   # opcional; senão, match pelo JDBC URL
  environment: ${LQM_ENV:${spring.profiles.active}}
  enforce: false                           # true = avalia checks e aborta startup em violação bloqueante
  fail-closed: false                       # true = se o LiquiManager estiver fora, aborta (só p/ PRD se desejado)
  timeout: 3s
  buffer-dir: /tmp/lqm-buffer              # relatórios não enviados (reenvio no próximo start ou thread de retry)
```

### 1.2 Implementação

- `LqmLiquibaseAutoConfiguration` com `@AutoConfiguration(before = LiquibaseAutoConfiguration.class)` registra um
  `LqmSpringLiquibase extends SpringLiquibase` (o auto-config do Boot é `@ConditionalOnMissingBean(SpringLiquibase.class)`,
  então ele recua). As propriedades `spring.liquibase.*` são copiadas exatamente como o Boot faz (`LiquibaseProperties`).
- `LqmSpringLiquibase.performUpdate(Liquibase liquibase)` (método protegido do `SpringLiquibase`):
  1. Gera `operationId` (UUID v7) e coleta contexto (git commit via `META-INF/build-info.properties`/`git.properties`,
     host, versões, `contexts`, `labels`).
  2. Se `enforce`: busca políticas efetivas (cache 5 min + fallback em disco), roda `PolicyEngine.evaluate(changelog, database)`
     sobre os changesets **pendentes** (`liquibase.listUnrunChangeSets(contexts, labels)`); envia operação `checks run`;
     se `BLOCKED` → lança `LqmPolicyViolationException` (startup falha com mensagem clara e link para a tela 06).
  3. Registra `LqmChangeExecListener implements ChangeExecListener` (`willRun`, `ran`, `runFailed`, `rolledBack`,
     `preconditionFailed`) que coleta outcome, ordem, duração e erro de cada changeset.
  4. Captura o SQL: executa antes `liquibase.update(contexts, labels, writer)` em modo *dry* **apenas se**
     `lqm.capture-sql=true` (custo de startup); por padrão captura só o SQL do changeset que falhou (do `MigrationFailedException`).
  5. `super.performUpdate(liquibase)`; em `finally`, monta o payload e envia assíncrono (com timeout); se falhar, grava em `buffer-dir`.
- Captura de log: `Appender` Logback temporário no logger `liquibase` durante a execução.

### 1.2.1 Estado da implementação (E3)

Testado num contexto Spring Boot **3.4.13 com Liquibase 4.29.2** (o que um serviço típico em Boot 3.4 traz), com o motor e o coletor
compilados contra a 5.x: é onde a compatibilidade do RNF-15 é de fato exercitada.

Diferenças em relação ao desenho acima:

- O envio é **síncrono**, limitado por `lqm.timeout`, e não numa thread. Com `migrate-only` o processo sai logo depois e uma
  thread assíncrona perderia o relatório. O buffer em disco cobre a falha.
- O log da operação é montado a partir do que o listener registrou (outcome, autor, duração, erro por changeset); o starter
  **não** pendura um appender no logging da aplicação. `lqm.capture-sql` não existe ainda: o SQL não vai no relatório.
- `lqm.url` aceita com ou sem o sufixo `/api/v1`.
- `lqm.fail-closed=true` consulta as políticas mesmo com `enforce=false`, porque a consulta é a prova de que o
  LiquiManager está no ar; se não responde, o startup é abortado.
- **Cache de 5 minutos e fallback em disco das políticas efetivas** (`PolicyCache`, no cliente, valendo também para a CLI): uma resposta é usada por 5 minutos sem perguntar
  de novo (um deploy e suas repetições, um serviço que reinicia em laço); depois disso o LiquiManager é consultado com o `ETag` do que se tem, e um pacote que não mudou
  custa um 304. Cada resposta também vai para um arquivo por (projeto, ambiente, changelog) em `<buffer-dir>/policies`, gravado inteiro e movido para o lugar, que
  sobrevive ao processo. Com o LiquiManager fora do ar, uma resposta de até **24 horas** vale no lugar dela (o log diz de quando é): um gate velho é mais estrito do que
  nenhum. Mais velha que isso, ou ilegível, não vale: segue sem gate (fail-open). `lqm.fail-closed=true` nunca usa o cache: ele consulta o servidor toda vez, porque a
  consulta é a prova de que o LiquiManager está no ar.

### 1.3 Modo "migrate-only" (recomendado para PRD)

Mesmo jar do serviço, executado **uma vez antes do rollout**, por quem implanta: um `Job`/`initContainer` no Kubernetes, um passo da
pipeline, um `docker run --rm` ou um comando no host (o produto não exige um orquestrador, D-19):

```bash
java -jar app.jar --spring.main.web-application-type=none --lqm.migrate-only=true
```

Com `lqm.migrate-only=true` o starter roda o update e encerra o contexto (`SpringApplication.exit`) com código 0/1.
No `Deployment` do serviço, `spring.liquibase.enabled=false`. Um exemplo para cada forma é uma tarefa em aberto.

---

## 2. CLI `lqm`

Jar executável (`lqm.jar`) e imagem `registry/<grupo>/lqm-cli:<versão>` (com drivers SQL Server, PostgreSQL, Oracle, MySQL). A imagem sai de
[`lqm-cli/Dockerfile`](../lqm-cli/Dockerfile) (`docker build -f lqm-cli/Dockerfile -t lqm-cli .`, contexto na raiz): usuário não-root, `lqm` no
PATH e **sem `ENTRYPOINT`**, porque o GitLab roda as linhas de `script:` por um shell dentro da imagem e um entrypoint as engoliria.

```bash
lqm <comando> [--changelog-file=...] [--url=jdbc:...] [--env=HML] [--project=gate] [--connection-key=core-hml] \
    [--contexts=...] [--labels=...] [--report=on|off] [--format=text|json]
```

| Comando | Equivalente Liquibase | Observação |
|---------|-----------------------|------------|
| `update`, `update-sql`, `update-count --count N`, `update-to-tag --tag T` | mesmo nome | `update-count` e `update-to-tag` passam pelo gate, que julga **só o que vai rodar** (os N primeiros, ou até o changeset que cria a tag); uma tag que o changelog não tem é falha, não um no-op |
| `rollback --tag`, `rollback-to-date --date`, `rollback-count --count N` | mesmo nome | |
| `rollback-one-changeset --changeset-id --changeset-author --changeset-path` | Pro | Implementado com `ChangeSet.rollback()` + `removeFromHistory`; fora da UI (desfaz um do meio sem os seguintes) |
| `rollback-to-changeset --changeset-id [--author --changeset-file --expect id::author::arquivo…]` | — | Desfaz o changeset e todos os posteriores, do mais novo para o mais antigo (`rollbackCount`); com `--expect`, recusa se o banco não bate com a lista pedida. É o que o runner executa |
| `tag`, `status`, `history`, `validate` | mesmo nome | |
| `diff --reference-url`, `diff-changelog --reference-url`, `snapshot` | mesmo nome | Operações **somente leitura** (não mexem em estado de changeset); nenhuma pede changelog. `diff` aceita `--fail-on-diff` (sai 1 se houver diferença); `snapshot` aceita `--snapshot-format json\|yaml\|txt` e `--snapshot-out arquivo`; `diff-changelog` aceita `--changelog-out arquivo` (a extensão escolhe o formato). Os mesmos tipos de objeto do drift do servidor, com o schema nomeado |
| `checks run`, `checks show` | Pro | `lqm-policy-engine` |
| `flow --flow-file=lqm.flow.yaml` | Pro (Flow) | Ver 2.2 |
| `login`, `logout` | — | `login` grava o endereço e o token em `~/.lqm/credentials` (ou em `LQM_HOME`), só legível pelo dono; o token vem de `LQM_TOKEN`, do console sem eco ou do stdin com `--token-stdin`, **nunca de flag**. Um token salvo só vai para o endereço para o qual foi salvo. Uso local: pipeline usa `LQM_URL` e `LQM_TOKEN` |

Credenciais do banco: `--username/--password` **somente** em uso local; em CI, `LQM_DB_USERNAME`/`LQM_DB_PASSWORD`
injetados como variáveis mascaradas do GitLab ou `--secret-ref=vault:secret/data/lqm/core-hml`. Com `--secret-ref` (ou `LQM_SECRET_REF`) **o próprio job**
pede as credenciais ao cofre, com o ambiente do provedor, então o LiquiManager nunca as vê nem as repassa (RNF-06): `vault:<caminho>` (KV v1 ou v2; `VAULT_ADDR` e
`VAULT_TOKEN`, ou AppRole com `VAULT_ROLE_ID` e `VAULT_SECRET_ID`, `VAULT_AUTH_PATH` e `VAULT_NAMESPACE`), `consul:<chave>` (`CONSUL_HTTP_ADDR`,
`CONSUL_HTTP_TOKEN`), `azure:<segredo>` (`AZURE_KEYVAULT_URL`, `AZURE_KEYVAULT_TOKEN`) e `env:<PREFIXO>` (`<PREFIXO>_USERNAME` e `<PREFIXO>_PASSWORD`). O segredo tem
`username` e `password`. `--username` ou `--password` dados valem mais e o `--secret-ref` nem é consultado. Se não resolve, **a execução falha** (não há banco
para abrir) com uma mensagem que nomeia o provedor e o status, nunca um valor. Para `diff`, a referência usa `--reference-url` e `LQM_REFERENCE_USERNAME` / `LQM_REFERENCE_PASSWORD`.

### 2.1 Saída padrão (exemplo usado no print da tela 03)

```
LiquiManager CLI 1.0.0 · Liquibase 5.0.0 · projeto gate
[PASS] checks       12 changesets validados · 0 violações      0.9s
[PASS] update       6 changesets aplicados em Core HML     1.8s
[PASS] tag          release-2.0.0                              0.2s
Relatório: https://lqm.<dominio>/operations/b2122559-...
```

### 2.1.1 Estado da implementação (E4)

Implementado: `update` (com `--tag`), `update-count`, `update-to-tag`, `update-sql`, `status`, `history`, `tag`, `rollback --tag`, `rollback-count`,
`rollback-to-date`, `rollback-one-changeset`, `rollback-to-changeset`, `validate`, `snapshot`, `diff`, `diff-changelog`, `checks run`, `login`/`logout`, `--secret-ref` e
`runner` (o runner de rollback, ver docs/04) e `flow`. Em `update-count` e `update-to-tag` o que fica de fora entra no relatório como `FILTERED` (motivo `scope`).

**Códigos de saída:** `0` sucesso · `1` a operação falhou · `2` uso incorreto (opção obrigatória ausente) ·
`3` bloqueado por política. O relatório é sempre o último passo e **nunca altera** o código de saída (ADR-004).

**Configuração por variável de ambiente** (cada uma tem a flag equivalente): `LQM_DB_URL`, `LQM_DB_USERNAME`,
`LQM_DB_PASSWORD`, `LQM_CHANGELOG_FILE`, `LQM_PROJECT`, `LQM_ENV`, `LQM_CONNECTION_KEY`, `LQM_CONTEXTS`, `LQM_LABELS`,
`LQM_URL`, `LQM_TOKEN`, `LQM_BUFFER_DIR`. A URL JDBC é sempre limpa de credenciais antes de ir ao LiquiManager (RNF-06).

**Fail-open com buffer:** se o LiquiManager não responde, o `update` roda sem gate e o relatório fica em
`~/.lqm/buffer` (um JSON por operação) até a próxima execução. Erros 5xx, 401/403, 408 e 429 são tentados de novo;
um 4xx que não vai melhorar (400/409/422) descarta o relatório em vez de travar a fila.

### 2.2 Flow file

```yaml
# lqm.flow.yaml  (na raiz do projeto)
version: 1
variables:
  release: ${CI_COMMIT_TAG:-snapshot}
stages:
  - name: checks
    run: checks run
    onFailure: stop            # stop | continue
  - name: preview
    run: update-sql
    when: env in [HML, PRD]    # opcional
    artifact: target/lqm/update.sql
  - name: update
    run: update
  - name: tag
    run: tag ${release}
  - name: drift
    run: diff --reference=snapshot
    onFailure: continue
```


#### Estado da implementação do flow (E18)

`lqm flow [--flow-file lqm.flow.yaml]` lê o arquivo, valida **tudo antes de rodar qualquer estágio** (YAML seguro, `version: 1`,
nomes únicos, condições, variáveis sem valor e comandos que o flow não sabe rodar) e só então executa. Cada estágio é o comando comum
que ele nomeia, com as opções do próprio flow, e **relata a si mesmo** como a operação que é (um estágio `update` é um update, com seus
changesets); o flow acrescenta uma operação `flow` sem changesets que diz como o todo terminou. Os estágios apontam para ela por
`parentOperationId`. Por isso um `flow` sem changesets **não conta como deploy** nos indicadores: contar o flow e seus estágios
dobraria todos os números (a definição de deploy da tela 01 vale para um `flow` só quando ele mesmo traz os changesets).

- Estágios aceitos: `update`, `update-sql`, `status`, `tag`, `validate`, `rollback`, `rollback-one-changeset`, `rollback-to-changeset` e `checks run`.
  **`diff` não é aceito**: o drift é verificado pelo servidor, não pela CLI; um flow com `diff` é recusado inteiro (exit 2).
- `when`: `env in [A, B]`, `env not in [A]`, `env == A` e `env != A`, comparando com `--env`. Nada além disso (sem `and`/`or`).
- `variables`: `${NOME}` e `${NOME:-padrão}`, primeiro entre as variáveis já declaradas, depois no ambiente do processo.
- `artifact`: no estágio `update-sql`, grava o SQL gerado no caminho dado.
- Saída: o código do estágio que parou o flow, ou 0. Um estágio `onFailure: continue` que falha é relatado e não derruba o flow.

### 2.y Changeset editado depois de aplicado (`ChecksumChanged`)

Junto do gate de políticas, `lqm checks run` e `lqm update` perguntam ao servidor (`POST /policies/checksum-check`) quais changesets do changelog já
foram **executados** no ambiente com outro checksum. Cada um vira um achado `ChecksumChanged` (bloqueante por padrão) com o que mudou: as linhas
só na versão aplicada (`-`) e só na atual (`+`), lidas do repositório entre o commit que aplicou e o atual, **sem comentários** (eles não entram no
checksum). Se nenhuma linha que conta mudou, o achado só avisa e diz que parece equivalente (`validCheckSum`). O comentário do MR mostra o diff.
Também vira `ChecksumChanged` (sempre na severidade do pacote, `validCheckSum` não dispensa) o changeset cujo **id já foi aplicado com outro author ou arquivo** que o changelog
não declara mais: id, author e arquivo identificam o changeset no Liquibase, então para ele é um changeset novo, que roda de novo e costuma falhar no que já existe.
Se o antigo continua declarado, são dois changesets que dividem o id e nada é acusado. `classpath:` e barra inicial não fazem parte do arquivo.
O contrário também aparece: o que o ambiente **rodou e o changelog deixou de declarar** (`removed`). O Liquibase ignora, então nada quebra, mas o histórico do
banco deixa de bater com o repositório e um rollback ou um drift não consegue mais ser explicado por ele; pode ser de propósito (squash, baseline), então é só
**aviso** (no máximo MAJOR, mesmo com o check bloqueante no pacote). Só vale quando o coletor diz qual changelog leu (`changelog` no pedido, o mesmo `--changelog-file`
que ele reporta, comparado sem `classpath:`, `./` e barra inicial): sem isso, os changesets aplicados dos outros changelogs do projeto pareceriam removidos.
O MR roda contra um banco efêmero, onde o checksum nunca conflita: por isso a comparação usa o que o servidor sabe de cada ambiente
(`changeset_state.applied_checksum`, gravado quando uma execução ou o scanner o vê). Sem esse registro, ou sem o repositório, a coleta não acusa nada (ADR-004).

### 2.x Ordem dos ambientes (D-22)

**O que está em HML precisa estar antes em DEV, na mesma versão.** O `lqm update`, depois do gate de políticas e antes de aplicar, pergunta ao servidor
(`GET /policies/promotion-gate`) qual é o estágio anterior ao ambiente do `--env` no pipeline do projeto e o que esse estágio já executou
(`changeset_state`, com o checksum que o banco rodou). Se algum changeset que ele está prestes a aplicar não está lá, **ou está lá com outro checksum**
(o arquivo foi editado depois de rodar em DEV, então HML receberia algo que DEV nunca testou), **não aplica**: linha `[BLOCK] order`, operação
`BLOCKED` e código de saída 3, o mesmo do gate de políticas. Um `validCheckSum` que cobre o checksum que DEV rodou diz que as duas versões são a
mesma e libera; um checksum que o servidor não tem para o estágio anterior não é diferença ("na dúvida, não acusa"). Código: `OrderGate`. O primeiro estágio e um ambiente que não é estágio de pipeline nenhum do
projeto não são segurados. Consequência: o deploy do estágio anterior **precisa reportar** ao LiquiManager (token válido); um DEV nunca
reportado segura tudo, de propósito. Se o LiquiManager não responde, vale o fail-open (ADR-004): avisa `SKIP order` e segue.

---

## 3. GitLab CI

Template a publicar em `ci-cd/ci-templates/lqm.yml`:

```yaml
.lqm:
  image: registry/<grupo>/lqm-cli:1.0.0
  variables:
    LQM_URL: https://lqm.<dominio>/api/v1
    LQM_PROJECT: $CI_PROJECT_NAME
  script:
    - lqm flow --flow-file=lqm.flow.yaml --env=$LQM_ENV
      --changelog-file=src/main/resources/db/changelog/db.changelog-master.xml
      --connection-key=$LQM_CONNECTION_KEY

lqm-checks:            # em todo MR — não toca o banco
  extends: .lqm
  stage: test
  script: [ "lqm checks run --env=$LQM_TARGET_ENV --changelog-file=src/main/resources/db/changelog/db.changelog-master.xml" ]
  rules: [ { if: '$CI_PIPELINE_SOURCE == "merge_request_event"' } ]

db-migrate-hml:
  extends: .lqm
  stage: deploy-hml
  variables: { LQM_ENV: HML, LQM_CONNECTION_KEY: core-hml }
  environment: { name: hml }

db-migrate-prd:
  extends: .lqm
  stage: deploy-prd
  variables: { LQM_ENV: PRD, LQM_CONNECTION_KEY: core-prd }
  environment: { name: prd }
  when: manual
```

O `lqm-checks` em MR comenta no merge request (GitLab API) um resumo dos achados.

### 3.1 Estado da implementação do comentário em MR (E24)

`lqm checks run` posta o resumo **de dentro do job**, que já tem o veredito: nada vai ao servidor e volta. Precisa de um token de
projeto com escopo `api` na variável de CI `LQM_GITLAB_TOKEN` (mascarada); o `CI_JOB_TOKEN` do GitLab não escreve em MR. O alvo vem
das variáveis do próprio GitLab (`CI_API_V4_URL`, `CI_PROJECT_ID`, `CI_MERGE_REQUEST_IID`): fora de um pipeline de MR, ou sem o token,
o comando não faz nada a mais. `--mr-comment=off` desliga.

- **Um comentário por MR:** a primeira execução cria, as seguintes **editam** o mesmo, achado por um marcador oculto
  (`<!-- lqm-checks -->`). Um comentário novo a cada push enterraria a revisão.
- **Conteúdo:** veredito por extenso (`✕ Bloqueado`, `! Com avisos`, `✓ Aprovado`), ambiente alvo e uma tabela com resultado,
  severidade, check, id do changeset e mensagem, do mais grave ao menos grave, no máximo 30 linhas (o resto vira "e mais N achados").
- **Fail-open (ADR-004):** se o GitLab recusar ou não responder, a linha `[SKIP] mr-comment` registra o motivo (só o status HTTP,
  nunca o token) e o pipeline segue. Também não comenta quando o LiquiManager está fora e não houve veredito.
- **Exercitado contra um GitLab real** (a POC de `deploy/gitlab-poc`, GitLab CE): `lqm checks run` avaliou o changelog de exemplo,
  postou a nota no merge request e uma segunda execução **atualizou a mesma nota**, sem duplicar. O cliente e o texto também têm testes
  contra um servidor simulado. O encaixe na CLI (variáveis de CI e a linha `mr-comment`) não tem teste automatizado, porque lê
  `System.getenv`; ele foi verificado à mão.
- **Dentro do runner do GitLab:** o job `lqm-checks` da POC (`deploy/gitlab-poc`) roda `lqm checks run` na imagem `lqm-cli` num runner
  Docker, com o Postgres efêmero como serviço, lê o pacote efetivo do LiquiManager com um token de serviço, avalia o changelog do MR e
  posta a nota **como o bot do token de projeto** (apagada a nota, o job a recriou). O gate **bloqueante** (código de saída 3) não foi
  exercitado nesse runner: o changelog de exemplo só gera avisos em DEV.

---

## 4. Scanner agentless

Job do `lqm-worker`, por conexão com `scanner_enabled`:

1. Lê `SELECT ID, AUTHOR, FILENAME, DATEEXECUTED, ORDEREXECUTED, EXECTYPE, MD5SUM, DESCRIPTION, TAG, LIQUIBASE, CONTEXTS, LABELS, DEPLOYMENT_ID FROM <schema>.DATABASECHANGELOG WHERE DATEEXECUTED > :ultimoVisto`.
2. Agrupa por `DEPLOYMENT_ID`; para cada grupo sem operação conhecida com o mesmo `deployment_id` → cria operação
   `command=observed`, `source=scanner`, `status=SUCCESS` e finding `OutOfProcessChange`.
3. Atualiza `changeset_state`.
4. Também lê `DATABASECHANGELOGLOCK` e alerta lock preso > 15 min.

---

## 5. Integrações externas

| Sistema | Uso | Configuração |
|---------|-----|--------------|
| GitLab | Ler changelog por commit (Developer Mode, runner), trigger de promoção, comentário em MR | Token de grupo com `read_repository`, `api` |
| SSO (Azure AD ou Keycloak) | Login, grupos | Decisão D-03 |
| Vault / Consul KV / Azure Key Vault | Credenciais de banco | D-04; Consul KV já usado (`tools/consulkvsync`) |
| Serviço `email` (backend de e-mail da organização) | Notificações | Endpoint interno |
| Microsoft Teams | Notificações | Incoming webhook por canal |
| Prometheus/Grafana | Métricas da plataforma | `/actuator/prometheus` |

### 5.1 Webhook do GitLab (MR fechado ou reaberto)

O LiquiManager precisa saber quando um MR **foi fechado sem merge**, para encerrar os changesets que só ele tinha (aba *Em revisão* do projeto, docs/telas/04). Em vez de
consultar o GitLab (o que exigiria guardar uma credencial dele no servidor), o GitLab avisa: **um único endpoint para o workspace inteiro**, registrado **uma vez**, e não
um por projeto. O payload diz de que projeto é (`project.path_with_namespace`), e o LiquiManager acha o seu pelo caminho do repositório (`repo_url` termina em
`grupo/projeto`, qualquer que seja o host ou o esquema da URL).

- **Endpoint:** `POST /api/v1/integrations/gitlab/webhook`. **Autenticação:** um token de serviço com o escopo **`webhook`** (Administrar → Tokens), enviado no cabeçalho
  `X-Gitlab-Token`, que é o que o GitLab sabe mandar (o campo "Secret token" do webhook). Um token de projetos limitados só fala por eles.
- **Evento:** só *Merge request events*. `close` encerra o grupo do MR (com a observação "Encerrado automaticamente: o MR !N foi fechado no GitLab por <quem>", por `gitlab:<usuário>`,
  na auditoria); `reopen` o reabre. `merge` não precisa de nada: o próximo relatório da branch principal traz os changesets para o changelog. O resto responde 200 e é ignorado.
  Responde 200 também a projeto desconhecido, para o GitLab não desligar o webhook por falha.
- **Onde registrar, do melhor para o pior:**
  1. **Webhook de grupo** (Grupo → Configurações → Webhooks): vale para todos os projetos do grupo, atuais e futuros, como as variáveis do grupo. É recurso do **GitLab Premium/Ultimate**.
  2. **System hook** (Admin → System Hooks), numa instância *self-managed* sem o recurso acima: vale para a instância inteira (é o que a POC usa: `connect-lqm.py webhook`).
  3. **Webhook por projeto**, se nenhum dos dois existe. É o que se quer evitar: um por projeto.
- **Fechado antes de o pipeline reportar:** um MR pode ser fechado segundos depois de aberto, quando o pipeline dele ainda nem reportou e o webhook não tem o que encerrar. Por isso o
  LiquiManager **guarda o estado de cada MR** que o GitLab informou (`gitlab_merge_request`); quando o relatório chega depois, um MR já fechado nasce **encerrado**, em vez de listado.
- **Ordem dos eventos:** o GitLab entrega os webhooks de forma assíncrona e monta o payload quando o job roda, não quando o fato aconteceu: o evento de **criação** do MR pode chegar
  depois do `close` e com `state: opened` e um horário *posterior* ao dele. Por isso o estado vem da **ação** (`close`, `reopen`, `merge`), nunca do `state` do payload; a criação só diz
  "aberto" se nada mais é sabido; e `close`/`reopen` são ordenados pelo `updated_at` do evento (um `reopen` mais velho que o `close` seguinte é ignorado).
- **O que ele não cobre:** o GitLab **não avisa quando um MR é deletado**. Esse caso continua sendo encerrar o grupo à mão, com observação. Sem o webhook registrado, nada muda: o fluxo
  manual continua valendo para todos os casos.

### 4.1 Estado da implementação do scanner (E15)

Implementado como descrito acima, com estas decisões:

- **A primeira passada** sobre um banco importa o histórico (para a matriz saber o que rodou) **sem** o finding `OutOfProcessChange`:
  chamar todo deploy desde o início dos tempos de "fora do processo" enterraria os que importam. Depois, só o que é novo é julgado.
- Uma operação **conhecida** é qualquer uma com o mesmo `deployment_id` na mesma conexão. O Liquibase reutiliza o `DEPLOYMENT_ID` dentro de
  **um** processo; na prática cada deploy é um processo (pod, job, CLI), mas dois `update` seguidos na mesma JVM compartilham o id e o
  segundo não seria visto como novo.
- Operações `observed` ligam ao projeto do pipeline da conexão (ou a `unassigned`) e ao changelog que já conhece o changeset; sem isso,
  ao changelog `observed`.
- Linhas sem `DEPLOYMENT_ID` (Liquibase antigo) agrupam por dia.
- Só conexões **verificadas** são varridas (as descobertas pela ingestão não têm credencial). O intervalo é `scanner_interval_min` (15).
- Lock preso há mais de 15 minutos fica em `scanner_state.stuck_lock_since` e marca a execução do job como problema; **o alerta
  por e-mail/Teams só existe com o E19**.
- `GET /connections/{id}/scanner` e `POST /connections/{id}/scan`.
- **SQL Server** (o alvo real) foi exercitado em teste de integração (`SqlServerTargetIntegrationTest`, SQL Server 2022 do perfil
  `target` do compose): teste de conexão, importação do histórico, deploy fora do processo e drift. O que isso mostrou, e está tratado:
  - O Liquibase não aceita `setDefaultSchemaName` num schema que não seja o do login (`dbo`). A conexão usa o schema **explicitamente**
    (nas consultas do scanner, no escopo do snapshot e na comparação), e o drift num schema que não é o do login funciona.
  - **A conta de leitura precisa de `db_datareader` (para ler o `DATABASECHANGELOG`) e de `VIEW DEFINITION`.** O SQL Server não nega a
    consulta de metadados sem essa permissão: responde como se o valor padrão das colunas e o texto das views não existissem, e o drift
    acusaria diferenças que ninguém fez. Por isso o **drift se recusa a rodar** sem ela (a execução falha dizendo o motivo) e o teste de
    conexão devolve um aviso (`warnings`).
  - A URL JDBC do driver 13 é criptografada por padrão: um SQL Server com certificado próprio precisa de `trustServerCertificate=true`
    na URL (sem usuário nem senha, como sempre).
  - **Fuso:** `DATEEXECUTED` não tem fuso. O LiquiManager lê no fuso da JVM (UTC no servidor), então um SQL Server em horário local
    mostra o deploy deslocado pela diferença; a ordem e o "novo desde a última vez" continuam certos, porque são comparações entre valores
    do mesmo banco.

