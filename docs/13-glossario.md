# 13 — Glossário

| Termo | Significado |
|-------|-------------|
| **Changelog** | Arquivo (XML/YAML/JSON/SQL) que lista changesets; o "master" inclui os demais |
| **Changeset** | Unidade de mudança, identificada por `id` + `author` + caminho do arquivo |
| **DATABASECHANGELOG** | Tabela do Liquibase em cada banco com os changesets aplicados (`DEPLOYMENT_ID`, `MD5SUM`, `EXECTYPE`…) |
| **DATABASECHANGELOGLOCK** | Tabela de lock do Liquibase |
| **Context / Label** | Filtros que decidem se um changeset roda num ambiente (os projetos usam context = perfil Spring) |
| **Tag** | Marca um ponto na `DATABASECHANGELOG` para rollback (`rollback --tag`) |
| **Rollback** | Desfazer changesets; "direcionado" = um changeset específico |
| **Drift** | Diferença entre o estado real do banco e o esperado pelo changelog |
| **Mudança fora do processo** | Alteração aplicada sem passar pelo caminho governado (SSMS, CLI local em PRD) |
| **Break-fix** | Correção emergencial que pode pular estágios mediante aprovação |
| **Operação** | Uma execução de comando (update, rollback, diff, checks…) registrada pelo LiquiManager |
| **Gate** | Resultado da avaliação de políticas num estágio: passou, alertado, bloqueado |
| **Check / Política** | Regra avaliada sobre changesets ou banco |
| **Pacote de políticas** | Conjunto de checks configurados, atribuído a projeto/estágio |
| **Exceção governada** | Permissão temporária e aprovada para um check não bloquear |
| **Pipeline** | Sequência de ambientes por onde um changelog é promovido |
| **Coletor** | Componente que envia operações ao LiquiManager (starter, CLI, scanner) |
| **Runner** | Processo (`lqm runner`) que puxa e executa os rollbacks aprovados pela UI; roda em qualquer lugar, sem orquestrador |
| **SoD** | Segregação de funções — quem executa não aprova |
| **DORA** | Métricas de entrega: frequência de deploy, change failure rate, tempo de recuperação, lead/cycle time |
| **Fail-open** | Se o LiquiManager estiver fora, o deploy segue; fail-closed = deploy para |
