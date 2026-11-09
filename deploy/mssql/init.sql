-- The SQL Server target of the local environment: a database, the account that runs Liquibase, the account LiquiManager
-- reads with, and one that cannot see definitions (to prove drift refuses it). Local and disposable: the passwords are weak
-- on purpose. Idempotent.
--
--   docker exec -i lqm-sqlserver /opt/mssql-tools18/bin/sqlcmd -C -S localhost -U sa -P 'Lqm!Local123' -i /dev/stdin < deploy/mssql/init.sql
IF DB_ID('lqm_target') IS NULL CREATE DATABASE lqm_target;
GO
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = 'lqm_app') CREATE LOGIN lqm_app WITH PASSWORD = 'App!Local123', CHECK_POLICY = OFF;
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = 'lqm_ro') CREATE LOGIN lqm_ro WITH PASSWORD = 'Ro!Local123', CHECK_POLICY = OFF;
IF NOT EXISTS (SELECT 1 FROM sys.server_principals WHERE name = 'lqm_blind') CREATE LOGIN lqm_blind WITH PASSWORD = 'Blind!Local123', CHECK_POLICY = OFF;
GO
USE lqm_target;
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = 'lqm_app') CREATE USER lqm_app FOR LOGIN lqm_app;
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = 'lqm_ro') CREATE USER lqm_ro FOR LOGIN lqm_ro;
IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = 'lqm_blind') CREATE USER lqm_blind FOR LOGIN lqm_blind;
-- the application: what Liquibase runs as
ALTER ROLE db_owner ADD MEMBER lqm_app;
-- LiquiManager: read DATABASECHANGELOG and see every definition. Nothing more.
ALTER ROLE db_datareader ADD MEMBER lqm_ro;
GRANT VIEW DEFINITION TO lqm_ro;
-- reads, but is not allowed to see definitions
ALTER ROLE db_datareader ADD MEMBER lqm_blind;
DENY VIEW DEFINITION TO lqm_blind;
GO
