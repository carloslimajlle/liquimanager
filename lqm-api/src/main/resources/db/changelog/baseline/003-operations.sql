--liquibase formatted sql
--
-- Operations: every Liquibase execution we collect. Partitioned by month.
-- Reference: docs/05-modelo-de-dados.md § 2.3 and § 4

--changeset lqm:012-operation
-- Composite primary key (id, started_at) because Postgres requires the partition
-- key to be part of it. The id alone is the ingestion idempotency key (docs/06):
-- the upsert matches on id.
create table operation (
  id                   uuid not null,
  workspace_id         uuid not null,
  project_id           uuid,
  changelog_id         uuid,
  db_connection_id     uuid not null,
  command              varchar(40) not null,
  type                 varchar(30) not null,
  status               varchar(20) not null,
  source               varchar(20) not null,
  executed_by          varchar(120) not null,
  started_at           timestamptz not null,
  ended_at             timestamptz,
  duration_ms          bigint,
  deployment_id        varchar(20),
  liquibase_version    varchar(30),
  collector            varchar(40),
  collector_version    varchar(20),
  contexts             varchar(500),
  labels               varchar(500),
  tag                  varchar(255),
  git_commit           varchar(64),
  git_branch           varchar(255),
  ci_pipeline_id       varchar(60),
  ci_job_url           varchar(500),
  host                 varchar(255),
  exit_code            int,
  count_executed       int default 0,
  count_previously_run int default 0,
  count_filtered       int default 0,
  count_failed         int default 0,
  error_message        text,
  -- Large logs and SQL go to object storage (D-09); only the reference lives here
  log_ref              varchar(500),
  sql_ref              varchar(500),
  parent_operation_id  uuid,
  gate_result          varchar(10),
  pipeline_stage_id    uuid,
  raw                  jsonb,
  primary key (id, started_at)
) partition by range (started_at);
--rollback drop table operation;

--changeset lqm:013-operation-indexes
create index ix_operation_ws_started on operation (workspace_id, started_at desc);
create index ix_operation_conn_started on operation (db_connection_id, started_at desc);
create index ix_operation_status on operation (status, started_at desc);
--rollback drop index ix_operation_status;
--rollback drop index ix_operation_conn_started;
--rollback drop index ix_operation_ws_started;

--changeset lqm:014-operation-partitions splitStatements:false
-- Monthly partitions from 2026-01 through 2028-12. In production pg_partman takes
-- over and keeps partitions created ahead of time; this block only guarantees the
-- environment works from the first deployment, without depending on the extension
-- being installed.
do $$
declare
  first_month date := date '2026-01-01';
  last_month  date := date '2029-01-01';
  current_month date;
begin
  current_month := first_month;
  while current_month < last_month loop
    execute format(
      'create table if not exists %I partition of operation for values from (%L) to (%L)',
      'operation_' || to_char(current_month, 'YYYY_MM'),
      current_month,
      current_month + interval '1 month'
    );
    current_month := (current_month + interval '1 month')::date;
  end loop;
end $$;
--
-- Reversal: deliberately a no-op.
--   1. In Postgres, `drop table operation` (the rollback of changeset 012) also
--      drops every attached partition, so a full reversal has nothing to undo
--      here. Reverting ONLY this changeset in isolation would leave the
--      partitions behind — which does not happen in practice, since 012 follows
--      immediately.
--   2. A `do $$ ... $$` block will not work: the parser splits the --rollback
--      line at the first ";", and splitStatements does not apply to rollbacks.
--rollback select 1 where false

--changeset lqm:015-operation-default-partition
-- Safety net: without it, an insert outside the range above fails. It must stay
-- EMPTY — rows here make attaching a new monthly partition require a scan, which
-- can fail. Monitor count(*) on this table.
create table operation_default partition of operation default;
--rollback drop table operation_default;

--changeset lqm:016-operation-changeset
-- Which changeset was evaluated in which operation, and with what outcome.
create table operation_changeset (
  operation_id         uuid not null,
  operation_started_at timestamptz not null,
  changeset_id         uuid not null,
  outcome              varchar(20) not null,
  filter_reason        varchar(60),
  exec_order           int,
  started_at           timestamptz,
  duration_ms          bigint,
  error_message        text,
  primary key (operation_id, changeset_id)
);
create index ix_operation_changeset_cs on operation_changeset (changeset_id);
--rollback drop table operation_changeset;
