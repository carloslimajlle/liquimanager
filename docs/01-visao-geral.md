# 01 — Visão geral do produto

## 1. Problema

Hoje cada microserviço versiona o próprio banco com Liquibase OSS, mas:

- **Não há visibilidade central.** Para saber o que foi aplicado em PRD é preciso consultar a tabela
  `DATABASECHANGELOG` de cada banco, manualmente.
- **Não há governança.** Nada impede um changeset sem rollback, um `DROP TABLE`, um `GRANT` direto ou um índice
  único em tabela com duplicidades de chegar em produção. A revisão depende de pessoas.
- **Drift é invisível.** Alterações feitas direto no banco (hotfix via SSMS, por exemplo) só aparecem quando quebram algo.
- **Migrations rodam no startup do pod.** Uma falha de migration derruba o deploy do serviço e o diagnóstico
  fica espalhado em logs de pods.
- **Auditoria cara.** Responder "quem mudou o quê, quando, aprovado por quem" exige garimpar Git, GitLab e banco.

## 2. Objetivo

Construir uma plataforma interna — o **LiquiManager** — que seja o *control plane* das mudanças de banco,
equivalente às três camadas do Liquibase Secure:

| Camada | Pergunta que responde | Módulo no LiquiManager |
|--------|------------------------|----------------------|
| **Change Automation** | Como a mudança se move? | Execução padronizada (CLI/starter/flow), matriz de deploy, histórico de operações, rollback direcionado |
| **Change Governance** | Como a mudança é controlada? | Biblioteca de políticas, enforcement por gate, exceções, RBAC, segredos, evidências |
| **Change Intelligence** | Como a mudança é entendida? | Dashboard DORA, drift detection, detalhe de operação com logs, SQL e contexto de execução (análise por IA prevista como feature futura) |

O sistema **não substitui o Liquibase**: ele usa o Liquibase OSS (`liquibase-core`) como motor de execução
e adiciona, por cima, coleta, políticas, visibilidade e controle.

## 3. Metas mensuráveis (preencher a linha de base)

| Métrica | Linha de base atual | Meta 6 meses após go-live |
|---------|---------------------|---------------------------|
| % de bancos com mudanças reportadas ao LiquiManager | `[0%]` | 100% dos bancos de PRD |
| % de changesets com rollback | `[100%]` | ≥ 95% |
| Falhas de migration em PRD / mês | `[20%]` | −70% |
| Tempo médio para diagnosticar falha de migration (MTTR) | `[2 horas]` | < 30 min |
| Drift não explicado em PRD | desconhecido | 0 itens abertos > 7 dias |
| Tempo para produzir evidência de auditoria | `[4 horas]` | < 5 min (export) |

## 4. Personas

| Persona | Necessidade principal | Telas principais |
|---------|----------------------|------------------|
| **Desenvolvedor** | Escrever changesets corretos, validar localmente, saber se passou nos checks | Developer Mode, Database Change, All Operations |
| **DBA** | Aprovar mudanças de PRD, investigar falhas, eliminar drift, rollback preciso | Policy Enforcement, Drift Detection, Database Change, Deployment Matrix |
| **Release Manager / Tech Lead** | Saber o que está pronto para promover e o que está pendente por ambiente | Deployment Matrix, Operations Dashboard |
| **Segurança / Compliance** | Garantir segregação de funções, evidências, nada de segredo em changelog | Acessos e Permissões, Policy Library, export de auditoria |
| **Gestão (Coordenação/Gerência de TI)** | Indicadores de entrega e risco | Operations Dashboard |
| **Pipeline / Agente de IA** | Executar mudanças pelo mesmo caminho governado que humanos | API de ingestão, CLI `lqm`, tokens de serviço |

## 5. Escopo

### Dentro do escopo (produto completo)

1. Cadastro de **workspaces, projetos, changelogs, bancos (conexões), ambientes e pipelines**.
2. **Coleta** de toda operação Liquibase (`update`, `update-sql`, `rollback*`, `tag`, `status`, `diff`,
   `diff-changelog`, `checks run`, `flow`) via três coletores: Spring Boot starter, CLI `lqm` e scanner agentless.
3. **Motor de políticas** próprio com 50+ checks pré-construídos, checks customizados (regex/SpEL/SQL),
   pacotes reutilizáveis e severidade 0–4 com bloqueio.
4. **Gates por ambiente** em pipelines (DEV → TST → HML → PRD) com aprovação e exceções com prazo. (Ser possivel configurar os ambientes disponiveis)
5. **Matriz de deploy** changeset × ambiente.
6. **Drift detection** agendado e sob demanda, comparando banco × changelog e banco × banco.
7. **Rollback direcionado** (one-changeset, to-tag, to-date) com aprovação.
8. **Dashboard DORA**: frequência de deploy, change failure rate, rollback rate, cycle time.
9. **RBAC** com segregação de funções, SSO (Azure AD/Keycloak — ver decisões), segredos externos, trilha de auditoria imutável.
10. **Developer Mode**: extensão VS Code / IntelliJ que expõe comandos `lqm` no editor (fase 3).

### Fora do escopo

- Reimplementar o Liquibase ou fazer fork do `liquibase-core`.
- Executar DDL arbitrário fora de changesets (o produto **detecta** isso; não o promove).
- Backup/restore de bancos.
- Mascaramento de dados / gestão de dados de teste.
- Suporte a bancos que o `liquibase-core` OSS não suporta.

## 6. Bancos suportados

Prioridade 1 (usados hoje): **SQL Server**. Prioridade 2: PostgreSQL, Oracle, MySQL.
Qualquer banco suportado pelo `liquibase-core` funciona para coleta; checks que analisam SQL gerado e o
drift dependem do suporte do `liquibase-core` ao snapshot daquele banco.

## 7. Mapa de funcionalidades: Liquibase Secure × LiquiManager

Fonte: datasheet *Liquibase Secure Platform* (prints em `docs/prints/referencia/`).

| Item do datasheet | Funcionalidade no LiquiManager | Tela | Fase |
|-------------------|------------------------------|------|------|
| Version-controlled database changes | Cadastro de changelogs vinculados a repositório Git; leitura do changelog no commit | 03, 10 | 1 |
| Local validation before deployment | `lqm checks run` e `lqm validate` locais (CLI) | 03 | 1 |
| Broad database object support | Herdado do `liquibase-core` | — | 1 |
| Guided developer experience | Developer Mode (extensão IDE) + mensagens dos checks com correção sugerida | 03 | 3 |
| Liquibase Flows | `lqm.flow.yaml` (estágios: checks → update → tag) | 03, 07-coletores | 2 |
| CI/CD deployment automation | Templates GitLab CI + modo `migrate-only` no Spring | 07-coletores | 1 |
| Multi-environment deployment | Pipelines com ambientes ordenados e promoção | 04 | 2 |
| Developer self-service | Execução em DEV/TST pelo próprio dev, sem DBA | 03, 09 | 2 |
| Environment consistency | Deployment Matrix | 04 | 1 |
| Out-of-process change detection | Drift detection + changesets fora do pipeline | 06, 09 | 2 |
| Deployment history and outcomes | All Operations + Database Change | 02, 05 | 1 |
| Targeted rollback | Rollback one-changeset / to-tag com aprovação | 02, 05 | 2 |
| 50+ prebuilt policy checks | Catálogo (ver 08-motor-de-politicas) | 07 | 1 (20) / 2 (50+) |
| Custom policy checks | Checks regex, SQL-pattern e SpEL | 07 | 2 |
| Reusable policy packages | Pacotes de checks atribuídos a projetos/pipelines | 07 | 2 |
| Centralized policy management | Policy Library | 07 | 1 |
| Flag violations early / block risky patterns | Gates por ambiente com severidade bloqueante | 06 | 1 |
| Surface manual edits and emergency fixes | Marcação `break-fix` + detecção de execução fora do pipeline | 06 | 2 |
| Detect out-of-process changes and drift | Drift detection | 09 | 2 |
| RBAC and separation of duties | RBAC com regras SoD | 08 | 2 |
| Governed exceptions and approval workflows | Exceções com prazo e aprovação | 06, 08 | 2 |
| Secrets management | Integração Vault / Consul KV / Azure Key Vault | 08, 10 | 2 |
| Audit-ready evidence | Trilha de auditoria imutável + export CSV/PDF | 08 | 2 |
| Change activity across teams and databases | Operations Dashboard | 01 | 1 |
| Deployment status across environments | Deployment Matrix | 04 | 1 |
| DORA metrics and delivery health | KPIs DORA | 01 | 2 |
| Pipeline and database-level visibility | Filtros por pipeline/banco em todas as telas | 01, 05 | 1 |
| Schema drift / failed deployments | Drift Detection, filtros de falha | 09, 05 | 2 |
| Centralized change and audit history | All Operations + auditoria | 05, 08 | 1 |
| Detailed logs and execution context | Database Change (logs, contexto, SQL) | 02 | 1 |
| AI-driven analysis / guided remediation | **Feature futura, desativada** (D-13). Nas fases 0–3 a tela 02 entrega erro, SQL gerado, contexto de execução e o status do changeset nos demais ambientes para diagnóstico manual | 02 | futuro |

## 8. Premissas e restrições

- O Liquibase continua sendo executado pelos projetos (no startup ou no pipeline); o LiquiManager **observa e
  governa**. Executar mudanças **a partir** do LiquiManager (botão "rodar update") é opcional e controlado por RBAC.
- Nenhuma credencial de banco é armazenada em texto no LiquiManager; somente referências a segredos.
- A plataforma é ela própria versionada com Liquibase (dogfooding).
- Idioma da interface: **pt-BR**. Termos técnicos do Liquibase (changeset, changelog, rollback, update, tag,
  drift, context, label) permanecem em inglês.
