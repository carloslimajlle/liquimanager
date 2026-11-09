# 11 — Roadmap e backlog

Time: 2 backend + 1 frontend + 0,5 DBA. Início em **02/11/2026**.

## Calendário

| Fase | Duração | Início | Fim | Marco de saída |
|------|---------|--------|-----|----------------|
| 0 — Fundação | 2 sem | 02/11/2026 | 13/11/2026 | Monorepo, banco, SSO e shell do frontend de pé em DEV |
| 1 — MVP: visibilidade + checks | 6 sem | 16/11/2026 | 08/01/2027 | `auditoria` e `gate` reportando DEV→PRD; matriz conferida; checks bloqueando em PRD no piloto |
| 2 — Governança completa + drift | 6 sem | 11/01/2027 | 19/02/2027 | `enforce=true` em HML e PRD; drift rodando em todas as conexões; auditoria exportável |
| 3 — Experiência do dev | 4 sem | 22/02/2027 | 19/03/2027 | Developer Mode web, extensão IDE, timeline e promoção |

Premissas do calendário:

- 18 semanas de trabalho, mais **recesso de 21/12/2026 a 01/01/2027** (2 semanas, já embutidas na fase 1 — por
  isso ela termina em 08/01 e não em 25/12).
- Feriados locais e Carnaval **não** foram descontados; cada um empurra o fim em ~1 dia útil.
- As datas valem a partir do momento em que a Fase 0 começar de fato. Se o início escorregar, desloque o bloco
  inteiro — as durações é que foram estimadas, não as datas.

## Fase 0 — Fundação (2 sem · 02/11/2026 – 13/11/2026)

- [x] Monorepo e CI do próprio LiquiManager: `.gitlab-ci.yml` (lint do changelog, seed em dia, contrato, `update → rollback → update`, web e Java) e `.github/workflows/ci.yml` (o mesmo mais SQL Server, Storybook e as imagens)
- [x] Imagens `lqm-api`, `lqm-cli` e `lqm-web` (Nginx, configurada ao subir: uma imagem para todos os ambientes), construídas e com teste de fumaça no CI
- [ ] Publicar as imagens num registry: o CI as constrói e testa, mas nenhum pipeline as publica (e o do GitLab ainda não as constrói)
- [ ] Ambientes DEV/HML do próprio LiquiManager: é infraestrutura, fora do repositório (hosts, domínio, SSO e banco de cada um); o repositório entrega o compose, a segunda réplica (`docker-compose.ha.yml`) e as imagens
- [x] Banco do LiquiManager versionado com Liquibase (25 changelogs, todos reversíveis: o `SchemaRoundTripIntegrationTest` roda `update → rollback total → update` num banco vazio) e seeds: 4 ambientes, 7 templates de papel, 55 checks (51 configuráveis e 4 de processo) e o pacote `default`
- [x] SSO (OIDC com Keycloak, Authorization Code + PKCE) + `/me` + shell do frontend (sidebar, topbar, rotas, tokens do design system)
- [x] Storybook com `KpiCard`, `StatusBadge`, `EnvBadge`, `CommandChip`, `DataTable` conferidos contra os prints

## Fase 1 — MVP: visibilidade + checks básicos (6 sem · 16/11/2026 – 08/01/2027)

| Épico | Histórias | Telas |
|-------|-----------|-------|
| E1 Catálogo | CRUD workspace/projeto/changelog/ambiente/conexão (com teste de conexão via segredo)/pipeline; tokens de serviço | 10 |
| E2 Ingestão | Endpoint idempotente; resolução de conexão; upsert changeset/estado; SSE | — |
| E3 Starter | Auto-config, listener, envio assíncrono com buffer, fail-open; piloto no serviço `auditoria` | — |
| E4 CLI | `update`, `status`, `tag`, `rollback --tag`, `checks run`, `validate`; imagem; template GitLab | — |
| E5 Policy engine v1 | Interface, 20 checks fase 1 mais usados, pacote default, gate por ambiente | 06 (parcial), 07 (somente leitura) |
| E6 Operações | All Operations + Database Change | 05, 02 |
| E7 Matriz | Deployment Matrix (modo Matriz) | 04 |
| E8 Dashboard v1 | Faixa "Mudanças" (sem DORA completo) + atividade ao vivo | 01 (parcial) |

**Critério de saída do MVP:** `auditoria` e `gate` reportando DEV→PRD; matriz correta comparada com `DATABASECHANGELOG`; checks bloqueando em PRD com `enforce=true` no piloto.

## Fase 2 — Governança completa + drift (6 sem · 11/01/2027 – 19/02/2027)

| Épico | Histórias | Telas |
|-------|-----------|-------|
| E9 Policy Library | Edição, checks customizados (REGEX, SQL_PATTERN, SPEL), encadeados, pacotes, dry-run | 07 |
| E10 Exceções e break-fix | Fluxo de solicitação/aprovação, expiração, SoD | 06, 08 |
| E11 RBAC/SoD | Bindings por escopo, templates, negações, regras SoD, tela de acessos | 08 |
| E12 Segredos | Provedores Vault/Consul/AKV, painel de rotação | 08 |
| E13 Auditoria | Hash encadeado, painel, export | 08 |
| E14 Drift | Snapshots de referência, job agendado, itens, aceitar, diff-changelog | 09 |
| E15 Scanner | Leitura de DATABASECHANGELOG, OutOfProcessChange, lock preso | 06 |
| E16 DORA | `metric_daily`, faixas Políticas e Drift do dashboard, tendências | 01 |
| E17 Rollback direcionado | "voltar até um changeset" (`rollback-to-changeset`: ele e os posteriores, em ordem inversa, com a lista à vista), aprovação, runner (processo, sem orquestrador) | 02, 04 |
| E18 Flow | `lqm.flow.yaml`, `lqm flow` | 03 |
| E19 Notificações | E-mail, Teams | — |

## Fase 3 — Experiência do dev (4 sem · 22/02/2027 – 19/03/2027)

| Épico | Histórias | Telas |
|-------|-----------|-------|
| E21 Developer Mode web | Visualização do changelog via GitLab API, KPIs | 03 |
| E23 Timeline + promoção | Modo Timeline, botão promover (trigger GitLab) | 04 |
| E24 Comentário em MR | Resumo dos checks no merge request | — |
| E25 Checks fase 3 | `CreateIndexOnline`, `PiiColumnEncryption`, `CursorUsageWarn` | 07 |

## Backlog futuro (sem data)

| Épico | Histórias | Telas |
|-------|-----------|-------|
| E22 Extensão IDE | VS Code (TypeScript) e IntelliJ (opcional): menu "LiquiManager", terminal. **Adiada** (era da fase 3) | 03 |
| Piloto e adoção | Plano de adoção, passos 1 e 2: piloto em `auditoria` e starter nos demais serviços. **Adiado** | — |
| Papel padrão opcional | Configuração do workspace, **desligada por padrão**: "papel padrão para quem entra pela primeira vez" (por exemplo, Leitura), restrito a quem está em algum grupo conhecido e não a todo o SSO. Hoje o padrão é nenhum acesso (menor privilégio) e o acesso vem do **grupo** do Keycloak; leitura aqui expõe SQL, bancos e auditoria, por isso só como escolha explícita. **Futura** | 10 (Configurações) |
| E20 IA | Analisar issues, Resumir, sanitização, auditoria de uso. **Desativada por padrão** (D-13); exige provedor definido e aprovação de Segurança antes de entrar em alguma fase | 02 |

## Plano de adoção nos projetos

1. Piloto: `backend/auditoria` (menor), depois `gate`.
2. Adicionar starter em todos os serviços com `liquibase-core` (`grep -rl liquibase --include=pom.xml backend`).
3. Habilitar scanner em todos os bancos (cobre quem ainda não tem starter).
4. Ligar `enforce=true` em HML → depois PRD.
5. Migrar PRD para `migrate-only` em Job antes do rollout (D-05).
