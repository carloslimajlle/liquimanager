# 06 — API REST

Base: `/api/v1`. JSON UTF-8. Datas ISO-8601 UTC. IDs UUID. Documentação OpenAPI gerada via springdoc em `/api/docs`.

## Convenções

- **Autenticação:** usuários → `Authorization: Bearer <JWT OIDC>`; coletores → `Authorization: Bearer lqm_<prefix>_<secret>` (token de serviço).
- **Workspace:** header `X-LQM-Workspace: <slug>` (opcional; sem ele vale o workspace `default`, D-23).
- **Paginação:** `?page=0&size=25&sort=startedAt,desc` → resposta `{ "content": [...], "page": 0, "size": 25, "totalElements": 1284 }`.
- **Erros:** RFC 9457 (`application/problem+json`): `{ "type", "title", "status", "detail", "code", "errors": [{ "field", "message" }] }`.
- **Códigos de negócio:** `LQM-001` recurso não encontrado · `LQM-010` violação de SoD · `LQM-020` gate bloqueado ·
  `LQM-030` exceção expirada · `LQM-040` changeset sem rollback · `LQM-050` segredo inacessível · `LQM-060` IA desabilitada (feature futura) · `LQM-070` o GitLab recusou o gatilho de promoção · `LQM-080` credenciais do break-glass não aceitas · `LQM-081` break-glass bloqueado por tentativas (429) · `LQM-403` proibido para este token/usuário · `LQM-422` payload inválido.

## Conta break-glass

| Método | Caminho | Descrição |
|--------|---------|-----------|
| POST | `/auth/break-glass` | `{ "username", "password", "code" }` → `{ "token", "expiresAt" }`. Só com o recurso ligado (senão 404); 401 `LQM-080`, 429 `LQM-081`. Ver docs/09 § 8.4 |
| POST | `/auth/break-glass/logout` | Revoga a sessão do token usado → 204 |

## Ingestão

### `POST /ingest/operations` — escopo `ingest`

Enviado pelos coletores ao fim de cada operação (e opcionalmente no início com `status=RUNNING`, depois atualizado).

```json
{
  "operationId": "b2122559-7b74-4182-9a51-0f0e2c1d7a10",
  "command": "update",
  "status": "FAILURE",
  "source": "pipeline",
  "executedBy": "p.ricardo",
  "startedAt": "2026-09-15T19:07:03.120Z",
  "endedAt": "2026-09-15T19:07:04.118Z",
  "project": "gate",
  "connection": { "key": "core-prd", "jdbcUrl": "jdbc:sqlserver://sql-prd:1433;databaseName=Core", "engine": "SQLSERVER", "dbVersion": "16.0.4135" },
  "changelog": { "path": "db/changelog/db.changelog-master.xml", "format": "XML", "complete": true },
  "environment": "PRD",
  "deploymentId": "8123456789",
  "contexts": "prd", "labels": "release-2.1.0", "tag": null,
  "git": { "commit": "9f3c2ab", "branch": "main" },
  "ci": { "provider": "gitlab", "pipelineId": "88123", "jobUrl": "https://gitlab/.../jobs/991" },
  "runtime": { "liquibaseVersion": "5.0.0", "collector": "lqm-cli", "collectorVersion": "1.0.0", "host": "runner-7f9", "javaVersion": "21.0.4" },
  "summary": { "executed": 0, "previouslyRun": 16, "filtered": 9, "failed": 6 },
  "changesets": [
    { "id": "SUP-3512-002", "author": "p.ricardo", "filePath": "db/changelog/work/SUP-3512/002-add-uk-placa-veiculo.xml",
      "checksum": "9:1a2b...", "labels": "release-2.1.0", "contexts": "dev,tst,hml,prd", "hasRollback": true,
      "outcome": "FAILED", "order": 17, "startedAt": "...", "durationMs": 830,
      "error": "Msg 1505: The CREATE UNIQUE INDEX statement terminated because a duplicate key was found..." },
    { "id": "SUP-3440", "author": "t.nunes", "filePath": "...", "outcome": "FILTERED", "filterReason": "context" }
  ],
  "policy": { "gateResult": "PASSED", "findings": [ { "check": "RollbackRequired", "severity": 0, "result": "PASSED", "changeset": "SUP-3505", "message": "..." } ] },
  "error": { "message": "Migration failed for changeset ...", "exitCode": 1 },
  "log": "....(até 2 MB, truncado)....",
  "sql": "CREATE UNIQUE INDEX uk_veiculo_placa ON dbo.veiculo(placa);"
}
```

`changelog.complete` diz que `changesets` lista **todos** os changesets do changelog naquele commit (o coletor os classificou antes do comando). Num relatório completo da
branch principal do projeto, o que o servidor conhece e o relatório não tem é marcado como removido (docs/telas/04, "Changeset removido do changelog"). Coletores antigos não
mandam o campo e não mudam nada. Em pipeline de merge request, `ci` também traz `mergeRequestIid`, `mergeRequestTitle` e `mergeRequestUrl`; um relatório de branch que não
é a principal só registra os changesets como "em MR" (fora do changelog) até um relatório da principal os listar.

Respostas: `202 Accepted { "operationId": "...", "url": "https://lqm/operations/..." }` · `200` se já recebido (idempotente)
· `401/403` token inválido/escopo · `422` payload inválido.

Regras de ingestão:
1. Resolve `connection.key`; se ausente, faz match por `host_fingerprint` normalizado do `jdbcUrl`; se não achar, cria conexão "não verificada".
2. Upsert de `changeset` por (changelog, id, author, filePath); atualiza `changeset_state`.
3. Calcula `isBreakFix` se label contém `break-fix`.
4. Se `source` ∉ origens permitidas para o ambiente → cria finding `OutOfProcessChange`.
5. Publica evento `operation.ingested` (SSE + notificações + métricas incrementais).

### `GET /policies/effective?project=gate&env=PRD&changelog=<path>` — escopo `ingest` ou `checks:read`

Retorna o pacote efetivo resolvido (precedência estágio > changelog > projeto > workspace), com ETag para cache.

```json
{ "package": "gate-prd", "etag": "W/\"a91c\"", "blockThreshold": 3,
  "checks": [ { "code": "UniqueIndexRequiresDedup", "kind": "BUILTIN", "severity": 4, "parameters": {} } ],
  "exceptions": [ { "check": "GrantStatementReview", "changeset": "SUP-3509", "expiresAt": "2026-09-30T00:00:00Z" } ] }
```

## Catálogo (CRUD padrão: `GET` lista, `GET /{id}`, `POST`, `PUT /{id}`, `DELETE /{id}` = arquivar)

| Recurso | Caminho | Permissão escrita |
|---------|---------|-------------------|
| Workspaces | `/workspaces` | `workspace:admin` |
| Times | `/teams` | `catalog:edit` |
| Projetos | `/projects` | `catalog:edit` |
| Changelogs | `/projects/{id}/changelogs` | `catalog:edit` |
| Ambientes | `/environments` | `workspace:admin` |
| Conexões | `/connections` · `POST /connections/{id}/test` | `connection:edit` |
| Pipelines | `/pipelines` · `/pipelines/{id}/stages` | `pipeline:edit` |

## Operações

| Método | Caminho | Descrição | Tela |
|--------|---------|-----------|------|
| GET | `/operations?status=&type=&command=&env=&connection=&project=&changelog=&executedBy=&from=&to=&q=` | Lista | 05 |
| GET | `/operations/kpis?<mesmos filtros>` | `{ total, trendPct, successRate, succeeded, medianDurationMs, caughtBeforeProd, failuresNonProd }` | 05 |
| GET | `/operations/{id}` | Detalhe + contexto + summary | 02 |
| GET | `/operations/{id}/changesets?page=` | Changesets da execução | 02 |
| GET | `/operations/{id}/log` · `/sql` | Texto (stream) | 02 |
| GET | `/operations/{id}/report?format=html\|pdf` | Relatório exportável | 02 |
| POST | `/operations/{id}/rollback-requests` | `{ "mode": "ONE_CHANGESET\|TO_TAG\|TO_DATE", "changesetId", "tag", "date", "reason" }` | 02, 04 |
| POST | `/rollback-requests/{id}/approve` · `/reject` | SoD: aprovador ≠ solicitante | 02 |
| GET | `/stream/operations` | SSE: eventos `operation.ingested` | 01 |

## Matriz

| Método | Caminho | Descrição |
|--------|---------|-----------|
| GET | `/pipelines/{id}/matrix?release=&q=&page=` | `{ stages:[{env, connection}], kpis:{inSyncAll, total, environments, awaitingPromotion, failedWithFix}, rows:[{changeset, author, filePath, release, isBreakFix, contexts, cells:[{stageId, status, operationId, deployedAt}]}] }` |
| GET | `/pipelines/{id}/timeline?from=&to=` | Promoções ordenadas no tempo |
| POST | `/pipelines/{id}/stages/{stageId}/promote` | Dispara trigger GitLab (permite `promote:<env>`) |

## Governança

| Método | Caminho | Descrição | Tela |
|--------|---------|-----------|------|
| GET | `/policy-checks?category=&enabled=&kind=` | Biblioteca | 07 |
| GET/PUT | `/policy-checks/{id}` | Ver/editar (gera auditoria) | 07 |
| POST | `/policy-checks/{id}/copy` | Customizar um builtin | 07 |
| POST | `/policy-checks/{id}/test` | `{ "changelogContent": "...", "format": "XML" }` → findings (dry-run) | 07 |
| GET | `/policy-checks/summary` | `{ prebuilt, prebuiltCategories, custom, packages, pipelines, connections }` | 07 |
| CRUD | `/policy-packages` | Pacotes | 07 |
| GET | `/enforcement?pipeline=&from=&to=` | `{ header:{checksRun, violations, blocked, passRate}, gates:[{env, result, checks, bySeverity}], findings:{...paginado}, blockedBeforeProd:[...], manualEdits:[...], drift:{added, removed, modified, objects, connections} }` | 06 |
| CRUD | `/policy-exceptions` · `POST /{id}/approve` · `/revoke` | Exceções | 06, 08 |

## Inteligência

| Método | Caminho | Descrição | Tela |
|--------|---------|-----------|------|
| GET | `/dashboard?workspace=&period=7d` | `{ header, dora:{...}, policy:{...}, drift:{...} }` (ver fórmulas na tela 01) | 01 |
| GET | `/dashboard/activity?limit=9` | Últimas operações | 01 |
| GET | `/drift/summary?period=30d&filter=all\|drift\|clean` | KPIs + série semanal + "o que divergiu" | 09 |
| GET | `/drift/runs?…` · `/drift/runs/{id}` · `/drift/runs/{id}/items` | Execuções e itens | 09 |
| POST | `/connections/{id}/drift-runs` | Executa sob demanda | 09 |
| POST | `/drift/items/{id}/accept` | Aceita item com justificativa | 09 |
| POST | `/drift/runs/{id}/changelog` | Gera `diff-changelog` (XML) para download | 09 |
| POST | `/operations/{id}/ai-analyses` | *(futuro — D-13)* `{ "kind": "ANALYZE\|SUMMARIZE" }` → 202, resultado via GET/SSE. Responde `LQM-060` enquanto `ai_enabled = false` | 02 |
| GET | `/operations/{id}/ai-analyses/latest` | *(futuro — D-13)* Última análise | 02 |

## Administração

| Método | Caminho | Descrição | Tela |
|--------|---------|-----------|------|
| GET | `/access/bindings?scope=` | Tabela de acessos + `{groups, members, openExceptions, auditEvents}` | 08 |
| POST | `/access/bindings` | `{ principalId, roleTemplate, permissions[], scopeType, scopeId, expiresAt }` | 08 |
| DELETE | `/access/bindings/{id}` | Revogar | 08 |
| GET | `/principals?q=` | Busca usuários/grupos (sincronizados do IdP) | 08 |
| GET | `/secrets/status` | `[{ connection, provider, rotatedAt, ageDays }]` + `secretsInChangelogs` | 08 |
| GET | `/audit?from=&to=&actor=&action=` · `/audit/export?format=csv\|pdf` | Auditoria | 08 |
| CRUD | `/service-tokens` | Token mostrado uma única vez no `POST` | 10 |
| GET | `/me` | Usuário, permissões efetivas, workspaces | todas |
