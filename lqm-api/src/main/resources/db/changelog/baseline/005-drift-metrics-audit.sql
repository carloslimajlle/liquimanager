--liquibase formatted sql
--
-- Drift, snapshots, DORA metrics and the audit trail.
-- Reference: docs/05-modelo-de-dados.md § 2.5

--changeset lqm:024-drift-run
create table drift_run (
  id               uuid primary key,
  -- a drift run also shows up as an operation of type `drift`
  operation_id     uuid,
  db_connection_id uuid not null references db_connection,
  -- D-06: the default reference is the last clean snapshot
  reference_type   varchar(20) not null,
  reference_desc   varchar(255),
  status           varchar(20) not null,
  has_drift        boolean,
  added            int default 0,
  removed          int default 0,
  modified         int default 0,
  started_at       timestamptz not null,
  ended_at         timestamptz
);
create index ix_drift_run_conn on drift_run (db_connection_id, started_at desc);
--rollback drop table drift_run;

--changeset lqm:025-drift-item
create table drift_item (
  id              uuid primary key,
  drift_run_id    uuid not null references drift_run,
  change_type     varchar(10) not null,
  object_type     varchar(30) not null,
  object_name     varchar(500) not null,
  details         jsonb,
  accepted        boolean default false,
  accepted_by     varchar(120),
  accepted_reason text
);
create index ix_drift_item_run on drift_item (drift_run_id, accepted);
--rollback drop table drift_item;

--changeset lqm:026-schema-snapshot
-- The SnapshotSerializer JSON lives in object storage; only the reference is here.
create table schema_snapshot (
  id               uuid primary key,
  db_connection_id uuid not null references db_connection,
  operation_id     uuid,
  taken_at         timestamptz not null,
  is_reference     boolean not null,
  storage_ref      varchar(500) not null
);
create index ix_schema_snapshot_ref on schema_snapshot (db_connection_id, is_reference, taken_at desc);
--rollback drop table schema_snapshot;

--changeset lqm:027-ai-analysis
-- D-13: AI analysis is a future feature and starts switched off
-- (workspace.ai_enabled = false). The table is created along with the rest of the
-- schema and stays empty until the feature exists.
create table ai_analysis (
  id            uuid primary key,
  operation_id  uuid not null,
  kind          varchar(20) not null,
  model         varchar(60) not null,
  requested_by  varchar(120) not null,
  duration_ms   int,
  input_tokens  int,
  output_tokens int,
  result        jsonb not null,
  created_at    timestamptz not null
);
--rollback drop table ai_analysis;

--changeset lqm:028-metric-daily
-- DORA metrics materialised per day. dim_key is the md5 of the dimension, because
-- project_id/db_connection_id/environment_id may be null and null does not work
-- in a primary key.
create table metric_daily (
  day                  date not null,
  workspace_id         uuid not null,
  project_id           uuid,
  db_connection_id     uuid,
  environment_id       uuid,
  deployments          int,
  failed_deployments   int,
  rollbacks            int,
  cycle_time_hours_p50 numeric(10,2),
  checks_run           int,
  checks_passed        int,
  blocked              int,
  drift_runs           int,
  drift_found          int,
  drift_items          int,
  dim_key              char(32) not null,
  primary key (day, workspace_id, dim_key)
);
--rollback drop table metric_daily;

--changeset lqm:029-audit-event
-- RNF-09: append-only with a hash chain. hash = SHA-256(prev_hash || canonical content).
-- A daily job re-verifies the whole chain.
create table audit_event (
  seq          bigserial primary key,
  id           uuid not null,
  workspace_id uuid,
  at           timestamptz not null default now(),
  actor        varchar(120) not null,
  actor_type   varchar(10) not null,
  action       varchar(80) not null,
  target_type  varchar(40),
  target_id    varchar(80),
  before       jsonb,
  after        jsonb,
  ip           varchar(45),
  user_agent   varchar(300),
  prev_hash    char(64) not null,
  hash         char(64) not null
);
create index ix_audit_event_at on audit_event (at desc);
create index ix_audit_event_actor on audit_event (actor, at desc);
--rollback drop table audit_event;

--changeset lqm:030-audit-event-append-only splitStatements:false
-- Real append-only depends on the application user NOT having update/delete on
-- this table. In development Liquibase usually runs as the application user and
-- the lqm_app role does not even exist, so the statement guards itself.
--
-- Do not use `preconditions` here: with onFail:MARK_RAN the changeset is recorded
-- as run and its ROLLBACK executes anyway, failing with "role does not exist".
-- Guarding in both directions inside the SQL is what keeps the pair reversible.
do $$
begin
  if exists (select 1 from pg_roles where rolname = 'lqm_app') then
    execute 'revoke update, delete on audit_event from lqm_app';
  end if;
end $$;
--
-- Reversal: deliberately a no-op, not an oversight.
--   1. Undoing this would hand update/delete back to lqm_app on a table that
--      the next changeset down (029) drops immediately afterwards, since reversal
--      runs backwards, so the grant would not survive the next step anyway.
--   2. A `do $$ ... $$` will not work here: the parser splits the --rollback line
--      at the first ";", and splitStatements does not apply to rollbacks.
--rollback select 1 where false
