# 02 — Requisitos funcionais

Convenção: `RF-<módulo>-<nº>`. Prioridade **M** (must, MVP), **S** (should, fase 2), **C** (could, fase 3).
Cada requisito tem critérios de aceite (CA) verificáveis. As telas citadas estão em [`telas/`](telas/).

Módulos: **CAD** cadastros · **COL** coleta · **AUT** automação · **GOV** governança · **INT** inteligência ·
**ADM** administração/segurança · **IA** análise por IA (futuro).

---

## CAD — Cadastros

### RF-CAD-01 Workspaces (M)
Agrupador de topo (ex.: `default`, `portuario`, `corporativo`). Todo recurso pertence a um workspace.
- CA1: usuário só vê recursos de workspaces aos quais tem acesso.
- CA2: seletor "Todos os workspaces" no topo das telas de visão geral agrega apenas os permitidos.

### RF-CAD-02 Projetos (M)
Um projeto = um serviço/sistema dono de um ou mais changelogs (ex.: `gate`, `auditoria`, `tos`).
Campos: nome, slug, time responsável, repositório Git (URL), branch padrão, descrição, tags.
- CA1: slug único no workspace, `^[a-z0-9-]{2,64}$`.
- CA2: projeto pode ser arquivado (some das listas, histórico preservado).

### RF-CAD-03 Changelogs (M)
Campos: nome, projeto, caminho do arquivo master no repositório (`src/main/resources/db/changelog/db.changelog-master.xml`),
formato (XML, YAML, JSON, FORMATTED SQL), pacote de políticas associado.
- CA1: ao receber uma operação com `changelogPath` + `projectSlug` desconhecidos, o sistema cria o changelog automaticamente
  como "não verificado" e notifica admins (configurável).

### RF-CAD-04 Ambientes (M)
Lista ordenada configurável por workspace. Padrão: `DEV`(1), `TST`(2), `HML`(3), `PRD`(4).
Campos: código, nome, ordem, cor (token), `isProduction` (bool), exige aprovação (bool).
- CA1: badge de ambiente usa a cor configurada (ver design system).
- CA2: ambientes com `isProduction=true` ativam regras de SoD e bloqueios.

### RF-CAD-05 Conexões de banco (M)
Uma conexão = um banco físico/lógico num ambiente (ex.: `Core PRD`).
Campos: nome de exibição, ambiente, engine (SQLSERVER, POSTGRESQL, ORACLE, MYSQL…), JDBC URL **sem credenciais**,
schema(s) padrão, referência de segredo (provedor + caminho), projeto(s) vinculados, monitoramento de drift (on/off + cron).
- CA1: "Testar conexão" resolve o segredo no backend, abre conexão e retorna versão do banco; nada de credencial no frontend.
- CA2: a identificação de operações recebidas se dá por `connectionKey` (slug) **ou** por match de host+porta+database.

### RF-CAD-06 Pipelines (S)
Sequência ordenada de estágios, cada estágio = (ambiente, conexão, pacote de políticas, exige aprovação).
Ex.: `SDLC-GATE`: DEV(Core DEV) → TST → HML → PRD.
- CA1: um projeto pode participar de vários pipelines; a Deployment Matrix é sempre por pipeline.

### RF-CAD-07 Times (M)
Nome, membros (usuários/grupos), projetos. Usado no Dashboard ("Time").

---

## COL — Coleta de operações

### RF-COL-01 Ingestão de operações (M)
Endpoint `POST /api/v1/ingest/operations` recebe o relatório de uma operação Liquibase (contrato em
[06-api-rest.md](06-api-rest.md#ingestão)). Autenticação por token de serviço com escopo `ingest`.
- CA1: idempotente por `operationId` (UUID gerado pelo coletor); reenvio não duplica.
- CA2: payload até 10 MB; logs maiores são truncados no coletor com aviso.
- CA3: responde em < 300 ms (p95); processamento pesado (métricas, notificações) é assíncrono.

### RF-COL-02 Spring Boot starter (M)
Biblioteca `lqm-spring-boot-starter` para os serviços que rodam Liquibase no startup.
Detalhes em [07-coletores-e-integracao.md](07-coletores-e-integracao.md#spring-boot-starter).
- CA1: adicionar a dependência + 3 propriedades é suficiente para reportar.
- CA2: se o LiquiManager estiver fora, o serviço **sobe normalmente** (fail-open configurável) e o relatório fica em
  buffer local / é reenviado.
- CA3: com `lqm.enforce=true`, executa os checks antes do `update` e **aborta o startup** se houver violação bloqueante.

### RF-COL-03 CLI `lqm` (M)
Wrapper Java (jar executável + imagem Docker) que executa comandos Liquibase usando `liquibase-core` e reporta.
Comandos: `update`, `update-sql`, `rollback`, `rollback-one-changeset`, `rollback-to-changeset`, `rollback-to-date`, `tag`, `status`, `history`,
`diff`, `diff-changelog`, `checks run`, `validate`, `flow`.
- CA1: código de saída ≠ 0 em falha ou violação bloqueante.
- CA2: `--report=off` permite uso offline.

### RF-COL-04 Scanner agentless (S)
Job do servidor que lê periodicamente a `DATABASECHANGELOG` de cada conexão cadastrada e gera operações sintéticas do tipo
`observed` para changesets aplicados que não foram reportados por nenhum coletor.
- CA1: changesets descobertos assim são marcados "fora do processo" (alimentam Policy Enforcement › "Edições manuais").
- CA2: frequência configurável por conexão (padrão 15 min).

### RF-COL-05 Captura de contexto (M)
Toda operação registra: executor (usuário ou serviço), origem (`pipeline`, `startup`, `cli-local`, `scheduler`, `ui`),
ID do pipeline/job do GitLab, commit SHA, branch, versão do Liquibase, versão do coletor, host, contexts e labels usados,
duração, código de saída, log (stdout/stderr), SQL gerado (quando disponível).

---

## AUT — Automação

### RF-AUT-01 Histórico de operações — *All Operations* (M) · [tela 05](telas/05-all-operations.md)
Lista paginada de todas as operações, filtros por status, tipo, comando, ambiente, banco, projeto, changelog, executor,
período e busca livre.
- CA1: ordenação padrão por data desc; paginação server-side de 25/50/100.
- CA2: clique na linha abre o detalhe (RF-INT-03).
- CA3: KPIs do topo respeitam os filtros aplicados.

### RF-AUT-02 Deployment Matrix (M) · [tela 04](telas/04-deployment-matrix.md)
Grade changeset × ambiente do pipeline selecionado, com status por célula:
`Sucesso`, `Falhou`, `Pendente` (existe no changelog, não aplicado e ambiente anterior já aplicou), `Não alvo`
(context/label exclui o ambiente), `Rollback`, `Fora do processo`.
- CA1: o status vem da última operação conhecida por (changeset, conexão) — reconciliado com o scanner.
- CA2: KPIs: em sincronia em todos, nº ambientes, aguardando promoção, falhou com correção (`break-fix`) aplicada.
- CA3: modo **Timeline** mostra a mesma informação como linha do tempo de promoções.

### RF-AUT-03 Flow (S)
Arquivo `lqm.flow.yaml` declara estágios (`checks`, `validate`, `update-sql`, `update`, `tag`, `diff`), condições
(`onFailure: stop|continue`) e variáveis. Executado pela CLI `lqm flow`. Ver [07](07-coletores-e-integracao.md#flow-file).

### RF-AUT-04 Rollback direcionado (S)
Do detalhe de uma operação ou da matriz: "Rollback deste changeset" / "Rollback até a tag".
- CA1: em ambiente `isProduction`, exige aprovação de um usuário com `rollback:approve` diferente do solicitante.
- CA2: o rollback é executado pelo **runner** (`lqm runner`, um processo que roda em qualquer lugar que alcance a API e o banco) e gera uma nova operação vinculada à original.
- CA3: se o changeset não tem `<rollback>` e não é auto-reversível, o botão fica desabilitado com tooltip explicando.

### RF-AUT-05 Promoção (S)
Botão "Promover para <próximo ambiente>" na matriz dispara o job de pipeline configurado (GitLab API trigger).
- CA1: só habilitado se todos os gates do ambiente atual passaram.

### RF-AUT-06 Developer Mode (C) · [tela 03](telas/03-developer-mode.md)
Visualização web do changelog (somente leitura, a partir do Git) + extensão IDE com menu de contexto
"LiquiManager › Update / Rollbacks / Policy Checks / Flow / …".
- CA1: KPIs: changesets no repo, com rollback, comandos disponíveis, último flow.

---

## GOV — Governança

### RF-GOV-01 Biblioteca de políticas (M) · [tela 07](telas/07-policy-library.md)
CRUD de checks com parâmetros tipados (ver [08-motor-de-politicas.md](08-motor-de-politicas.md)).
- CA1: checks pré-construídos não podem ser excluídos, apenas copiados/customizados ou desativados.
- CA2: toda alteração de check gera evento de auditoria com diff antes/depois.
- CA3: modo "Visualizar"/"Editando" — edição exige permissão `policy:edit`.

### RF-GOV-02 Pacotes de políticas (S)
Conjunto nomeado de checks com parâmetros, atribuível a projeto, changelog ou estágio de pipeline.
- CA1: precedência: estágio do pipeline > changelog > projeto > padrão do workspace.

### RF-GOV-03 Checks encadeados (S)
Um check pode agrupar outros (ex.: `ChangesetStandards` = 3 checks) e é avaliado como um só (pior severidade).

### RF-GOV-04 Enforcement por gate (M) · [tela 06](telas/06-policy-enforcement.md)
Cada execução de checks em um estágio produz um resultado de gate: `passed`, `warned`, `blocked`.
- Regra: severidade 4 (Blocker) → `blocked`; 3 (Critical) → `blocked` em `isProduction`, `warned` nos demais;
  0–2 → `passed` com achados informativos. (Configurável por pacote.)
- CA1: um `update` em ambiente com gate `blocked` é recusado pelo coletor (starter/CLI) antes de executar SQL.
- CA2: a tela mostra 4 cards de gate, tabela de achados ordenada por severidade e painel lateral com bloqueios,
  edições manuais e drift.

### RF-GOV-05 Exceções governadas (S)
Solicitar exceção para (check, changeset[, ambiente]) com justificativa e prazo (máx. configurável, padrão 30 dias).
- CA1: aprovação por usuário com `exception:approve` e que **não** seja o autor do changeset (SoD).
- CA2: exceção vencida volta a bloquear automaticamente.
- CA3: toda exceção aparece no painel "Exceções governadas" e na auditoria.

### RF-GOV-06 Break-fix (S)
Changeset marcado com label `break-fix` (ou criado via fluxo de emergência) pode pular gates intermediários
mediante aprovação; fica destacado em laranja em todas as telas.

### RF-GOV-07 Detecção de mudança fora do processo (S)
Changeset aplicado por origem não autorizada (ex.: `cli-local` em PRD, ou descoberto só pelo scanner) gera achado
`OutOfProcessChange` (severidade configurável, padrão 3).

---

## INT — Inteligência

### RF-INT-01 Operations Dashboard (M) · [tela 01](telas/01-operations-dashboard.md)
Três faixas de KPIs (Mudanças/DORA, Políticas, Drift) + tabela "Atividade de mudanças" ao vivo.
Fórmulas em [telas/01](telas/01-operations-dashboard.md#fórmulas).
- CA1: filtros de workspace e período (7/30/90 dias) no topo.
- CA2: tendência compara com o período anterior de mesmo tamanho.
- CA3: tabela atualiza via SSE sem recarregar a página.

### RF-INT-02 Drift Detection (S) · [tela 09](telas/09-drift-detection.md)
Execução agendada (cron por conexão) e sob demanda de:
(a) `diff` banco × snapshot esperado (gerado aplicando o changelog em banco efêmero ou a partir do último snapshot "limpo");
(b) `diff` banco × banco de referência (ex.: HML × PRD).
Resultado: itens adicionados/removidos/modificados por tipo de objeto.
- CA1: KPIs, gráfico semanal (barras = itens, linha = drift rate), "O que divergiu" por tipo, tabela de execuções.
- CA2: botão "Gerar changelog do drift" (`diff-changelog`) para incorporar a mudança ao repositório.
- CA3: item de drift pode ser marcado "aceito" (com justificativa) para não reaparecer.

### RF-INT-03 Detalhe da operação — *Database Change* (M) · [tela 02](telas/02-database-change.md)
Cabeçalho (comando, status, id), contexto (banco, changelog, projeto, executor), fatos (início, fim, duração,
deployment id, breakdown dos changesets), log completo, SQL executado, changesets da execução.
- CA1: "Ver relatório" exporta HTML/PDF da operação.

### RF-INT-04 Métricas DORA (S)
Calculadas diariamente e materializadas (ver modelo de dados `metric_daily`).

### RF-INT-05 Notificações (S)
Regras: falha em PRD, gate bloqueado, drift detectado, exceção a vencer. Canais: e-mail (serviço `email` existente),
Microsoft Teams webhook, Slack (opcional).

---

## IA — Análise por IA (feature futura)

> **Prioridade F — futuro (D-13).** Não entra nas fases 0–3. Fica especificado para não se perder o desenho,
> e **desativado por padrão** (`workspace.ai_enabled = false`). Enquanto estiver desligado, o diagnóstico de
> falhas usa os dados já coletados — ver o painel "Diagnóstico da falha" na [tela 02](telas/02-database-change.md),
> que é o que de fato se implementa agora. Antes de ligar: aprovação de Segurança e definição do provedor.

### RF-IA-01 Análise de falha (F)
No detalhe de uma operação com falha: "Analisar issues" envia ao modelo o erro, o changeset, o SQL gerado,
versão do banco e metadados — **nunca dados de negócio nem credenciais** — e exibe: resumo do problema,
causa raiz, remediação guiada (passos numerados), correção sugerida (SQL) e "Copiar".
- CA1: mostra modelo, horário e duração da análise.
- CA2: resultado é salvo e auditado; reanálise cria nova versão.
- CA3: desativado por padrão; só aparece com `ai_enabled = true` **e** permissão `ai:use`.

### RF-IA-02 Resumir (F)
Botão "Resumir" gera resumo em linguagem natural da operação (para colar em incidente/ticket).

---

## ADM — Administração e segurança

### RF-ADM-01 Autenticação SSO (M)
OIDC (ver decisão D-03). Sem cadastro de senha local, exceto conta break-glass.

### RF-ADM-02 RBAC e SoD (M/S) · [tela 08](telas/08-acessos-e-permissoes.md)
Papéis, permissões e escopos conforme [09-seguranca-e-rbac.md](09-seguranca-e-rbac.md).

### RF-ADM-03 Tokens de serviço (M)
Tokens para coletores/pipelines, com escopo (`ingest`, `checks:read`, `runner`), projeto(s) e expiração; exibidos uma única vez.

### RF-ADM-04 Trilha de auditoria (M)
Todo evento de escrita (UI ou API) gera registro imutável (append-only, hash encadeado). Export CSV/PDF por período.

### RF-ADM-05 Gestão de segredos (S)
Conexões referenciam segredos em HashiCorp Vault, Consul KV (já usado — `tools/consulkvsync`) ou Azure Key Vault.
Painel mostra idade da última rotação e alerta > 14 dias (configurável) e detecta credenciais em changelogs (check `SecretInChangelog`).
