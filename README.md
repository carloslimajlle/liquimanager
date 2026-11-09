# LiquiManager — Plataforma de Governança de Mudanças de Banco

> Plataforma open source (Apache 2.0) inspirada no **Liquibase Secure (antigo Liquibase Pro)** para automatizar,
> governar e dar visibilidade às mudanças de banco feitas com **Liquibase OSS**.

[![CI](https://github.com/carloslimajlle/liquimanager/actions/workflows/ci.yml/badge.svg)](https://github.com/carloslimajlle/liquimanager/actions/workflows/ci.yml)
[![Licença](https://img.shields.io/badge/licen%C3%A7a-Apache%202.0-blue.svg)](LICENSE)

O LiquiManager **não substitui o Liquibase**: ele observa, governa e dá visibilidade às mudanças que o `liquibase-core` executa. Um
serviço que já roda Liquibase passa a reportar cada `update`, `rollback` e `tag` (por um starter Spring Boot ou pela CLI `lqm`); o
servidor guarda o histórico, avalia **políticas** antes do deploy (um check que falha pode bloquear), compara o banco com o último estado
limpo (**drift**), vigia quem mexeu fora do processo e mostra tudo numa interface web.

## O que existe hoje

| Área | Estado |
|------|--------|
| API, starter Spring Boot, CLI `lqm` e o motor de políticas, com os **51 checks configuráveis** do catálogo (os 4 de processo o sistema levanta sozinho) | Implementados e testados (`mvn verify`). |
| Telas 01 a 10 (dashboard, operação, Developer Mode, matriz com promoção, políticas, acessos, drift, cadastros) | Implementadas, em pt-BR, contra o contrato `contracts/openapi.yaml`. |
| Login (Keycloak, Authorization Code + PKCE), RBAC por permissão, segregação de funções, auditoria encadeada | Implementados. |
| Drift e scanner | Testados em PostgreSQL e em **SQL Server 2022 real**. |
| GitLab (leitura de arquivos, gatilho de promoção, comentário no MR) | Exercitado contra um GitLab CE real (`deploy/gitlab-poc`). |
| IA (D-13), extensão de IDE | **Não feitos.** A IA nasce desligada por decisão. |
| Rollback pela UI | Um runner (`lqm runner`, serviço ou pontual) que roda onde você quiser: Docker, systemd, cron, GitLab, Kubernetes (D-19). Exemplos em `deploy/examples/runner/`. |

As lacunas conhecidas de cada área estão no fim do documento correspondente, na seção "Estado da implementação" (por exemplo
[docs/07](docs/07-coletores-e-integracao.md), [docs/09](docs/09-seguranca-e-rbac.md) e [docs/telas/10](docs/telas/10-telas-de-suporte.md)).
**Código que diverge da especificação é bug; especificação que diverge da realidade se corrige no mesmo commit.**

## Rodando localmente

Pré-requisitos: Docker, Java 21 e Maven (para compilar e testar), Node 24 (para o front).

```bash
# Postgres, API, Keycloak e armazenamento S3 (a API aplica o próprio schema ao subir)
docker compose -f deploy/docker-compose.yml up -d --build

# a interface, em http://localhost:5173 (repassa /api para a porta 8080)
cd lqm-web && npm install && npm run dev
```

Sem emissor OIDC a API roda na **cadeia de desenvolvimento**: tudo é liberado e o cabeçalho `X-LQM-User` representa um usuário. Para
entrar de verdade pelo Keycloak, veja [deploy/keycloak](deploy/keycloak/README.md). Para observar um **SQL Server** (o alvo do produto),
veja [deploy/mssql](deploy/mssql/README.md); para o **GitLab** de teste, [deploy/gitlab-poc](deploy/gitlab-poc/README.md).

| Serviço | Porta |
|---------|-------|
| API | 8080 |
| PostgreSQL (`lqm`/`lqm`/`lqm`) | 5432 |
| Keycloak (`admin`/`admin`) | 8081 |
| Armazenamento S3 (RustFS) | 9000 e 9001 |
| SQL Server (perfil `target`) | 1433 |

## Estrutura

| Caminho | O quê |
|---------|-------|
| `docs/` | A especificação: 13 documentos, 10 telas, mockups e prints alvo |
| `contracts/openapi.yaml` | Contrato da API, escrito à mão |
| `lqm-policy-engine/` | Biblioteca pura com os checks, usada por API, CLI e starter |
| `lqm-core-client/` e `lqm-collector/` | Payloads, cliente HTTP e o que CLI e starter compartilham |
| `lqm-api/` | Spring Boot: REST, SSE, segurança. Dono do schema |
| `lqm-cli/` | O comando `lqm` |
| `lqm-spring-boot-starter/` | Auto-configuração para serviços que rodam Liquibase no startup |
| `lqm-web/` | A SPA (React, Vite, Tailwind) |
| `deploy/` | Compose do ambiente local, Keycloak, SQL Server e o GitLab de teste |

O guia para trabalhar no código, com as regras que não são óbvias, está em [CONTRIBUTING.md](CONTRIBUTING.md).

## Testes e CI

```bash
export LQM_TEST_DB_URL=jdbc:postgresql://localhost:5432/lqm_test DRIFT_TEST_USERNAME=lqm DRIFT_TEST_PASSWORD=lqm
mvn verify                       # todos os módulos; os de integração precisam de um PostgreSQL com a conta acima
cd lqm-web && npm test           # Vitest
```

O workflow [`ci.yml`](.github/workflows/ci.yml) roda a cada push e pull request: consistência (lint dos changelogs, seed igual ao catálogo
de `docs/08`, contrato OpenAPI), o backend contra um PostgreSQL **vazio** (inclui o `update → rollback de tudo → update` do schema do
próprio produto), drift e scanner contra um SQL Server, o front (tipos, testes, build) e a imagem da API. O Dependabot abre as
atualizações, mas **nunca** atualiza o Liquibase sozinho (D-17).

## Como ler

| # | Documento | Conteúdo |
|---|-----------|----------|
| 01 | [Visão geral do produto](docs/01-visao-geral.md) | Problema, objetivos, personas, escopo, mapa de funcionalidades vs. Liquibase Secure |
| 02 | [Requisitos funcionais](docs/02-requisitos-funcionais.md) | RFs por módulo (Automação, Governança, Inteligência, Administração) com critérios de aceite |
| 03 | [Requisitos não funcionais](docs/03-requisitos-nao-funcionais.md) | Desempenho, segurança, disponibilidade, observabilidade, retenção |
| 04 | [Arquitetura](docs/04-arquitetura.md) | Componentes, stack, fluxos, onde rodar |
| 05 | [Modelo de dados](docs/05-modelo-de-dados.md) | Entidades, DDL de referência, índices, retenção |
| 06 | [API REST](docs/06-api-rest.md) | Endpoints, payloads, códigos de erro, contrato de ingestão |
| 07 | [Coletores e integração](docs/07-coletores-e-integracao.md) | Spring Boot starter, CLI `lqm`, scanner agentless, GitLab CI, flow file |
| 08 | [Motor de políticas](docs/08-motor-de-politicas.md) | Catálogo de checks, parâmetros, severidade, pacotes, exceções |
| 09 | [Segurança e RBAC](docs/09-seguranca-e-rbac.md) | Papéis, permissões, segregação de funções, segredos, auditoria |
| 10 | [Design system](docs/10-design-system.md) | Tokens, tipografia, componentes, badges, regras de layout |
| 11 | [Roadmap e backlog](docs/11-roadmap-e-backlog.md) | Fases, épicos e histórias priorizadas |
| 12 | [Registro de decisões](docs/12-decisoes-pendentes.md) | D-01 a D-18, todas decididas e já refletidas nos demais documentos |
| 13 | [Glossário](docs/13-glossario.md) | Termos Liquibase e do produto |

### Especificação das telas

Cada tela tem um documento próprio com o **print alvo** (o que o sistema deve parecer), o print de referência
do datasheet, layout, componentes, origem de cada dado, estados e critérios de aceite.

| # | Tela | Módulo | Rota |
|---|------|--------|------|
| 01 | [Operations Dashboard](docs/telas/01-operations-dashboard.md) | Inteligência | `/` |
| 02 | [Database Change (detalhe da operação)](docs/telas/02-database-change.md) | Inteligência | `/operations/:id` |
| 03 | [Developer Mode](docs/telas/03-developer-mode.md) | Automação | `/projects/:id/changelogs/:clId` |
| 04 | [Deployment Matrix](docs/telas/04-deployment-matrix.md) | Automação | `/pipelines/:id/matrix` |
| 05 | [All Operations](docs/telas/05-all-operations.md) | Automação | `/operations` |
| 06 | [Policy Enforcement](docs/telas/06-policy-enforcement.md) | Governança | `/govern/enforcement` |
| 07 | [Policy Library](docs/telas/07-policy-library.md) | Governança | `/govern/checks/:id` |
| 08 | [Acessos e Permissões](docs/telas/08-acessos-e-permissoes.md) | Governança | `/admin/access` |
| 09 | [Drift Detection](docs/telas/09-drift-detection.md) | Inteligência | `/drift` |
| 10 | [Telas de suporte (cadastros)](docs/telas/10-telas-de-suporte.md) | Administração | `/admin/*` |

### Prints

- `docs/prints/mockups/*.png` — **prints alvo**. O sistema implementado deve ficar visualmente igual a eles.
  São gerados a partir do HTML em `docs/mockups/` (fonte editável).
- `docs/prints/referencia/*.jpeg` — prints originais do datasheet do Liquibase Secure (com banners de marketing
  por cima). Servem apenas como referência de inspiração; em caso de divergência, **vale o mockup**.


## Contexto que orientou o desenho

O produto nasceu de observar serviços como estes (levantados em um repositório real):

- Backends Spring Boot 3.4 / Java 21 com `liquibase-core` **embarcado** (`spring.liquibase.enabled=true`,
  `contexts: ${spring.profiles.active}`): as migrations rodam no **startup do pod**, não no pipeline.
- Banco principal SQL Server (`mssql-jdbc`), base `Core` (DEV: `Core_D30`).
- Changelogs em XML, organizados por ticket: `db/changelog/work/SUP-XXXX/NNN-descricao.xml`.
- CI/CD em GitLab CI (`ci-cd/ci-templates`), deploy em Kubernetes (`k8s`, `k8s-hml`, `k8s-prd`).
- Frontends React 19 + Vite + Tailwind 4 + TanStack Query + Zustand (monorepo Turbo).

Essas características orientam a arquitetura (ver [04-arquitetura.md](docs/04-arquitetura.md) e
[07-coletores-e-integracao.md](docs/07-coletores-e-integracao.md)).

## Licença

[Apache License 2.0](LICENSE). O `liquibase-core` 5.x é uma dependência sob a FSL (ver D-17 em [docs/12](docs/12-decisoes-pendentes.md)).
