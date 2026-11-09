# SQL Server alvo (ambiente local)

O produto existe para observar bancos SQL Server. O perfil `target` do compose sobe um SQL Server 2022 (limitado a 1,5 GB de memória):

```bash
docker compose -f deploy/docker-compose.yml --profile target up -d sqlserver
docker exec -i lqm-sqlserver /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P 'Lqm!Local123' -i /dev/stdin < deploy/mssql/init.sql
```

O `init.sql` cria o banco `lqm_target` e três contas (senhas fracas, só para uso local):

| Conta | Para quê |
|-------|----------|
| `lqm_app` | A aplicação: roda o Liquibase (`db_owner`). |
| `lqm_ro` | O LiquiManager: `db_datareader` + `VIEW DEFINITION`. É a conta que o scanner e o drift devem usar. |
| `lqm_blind` | Lê, mas sem `VIEW DEFINITION`: serve para provar que o drift se recusa a rodar com ela. |

**Por que `VIEW DEFINITION`:** o SQL Server não nega consulta de metadados sem a permissão, ele responde como se valores padrão de coluna
e texto de views não existissem, e o drift acusaria mudanças que ninguém fez (docs/07 § 4.1).

## Testes de integração

`SqlServerTargetIntegrationTest` (drift e scanner contra este SQL Server) só roda onde as variáveis abaixo existem:

```bash
export MSSQL_TEST_URL='jdbc:sqlserver://localhost:1433;databaseName=lqm_target;encrypt=true;trustServerCertificate=true'
export MSSQL_TEST_USERNAME=lqm_app MSSQL_TEST_PASSWORD='App!Local123'
export MSSQL_BLIND_USERNAME=lqm_blind MSSQL_BLIND_PASSWORD='Blind!Local123'   # opcional: o teste da conta sem VIEW DEFINITION
mvn -pl lqm-api -am test -Dtest=SqlServerTargetIntegrationTest
```

Para usar o SQL Server numa conexão do LiquiManager: JDBC URL sem usuário nem senha, provedor `ENV` e `secretRef` com o prefixo
(`MSSQL_RO` lê `MSSQL_RO_USERNAME` e `MSSQL_RO_PASSWORD` do ambiente da API).
