--liquibase formatted sql
--
-- Targeted rollback requested from the UI (docs/04 section 4.4, RF-AUT-04). A request is approved, then
-- claimed by a runner, which reports a new operation that answers the original one.

--changeset lqm:041-rollback-request
create table rollback_request (
  id                   uuid primary key,
  workspace_id         uuid not null,
  operation_id         uuid not null,
  operation_started_at timestamptz not null,
  db_connection_id     uuid not null references db_connection,
  kind                 varchar(20) not null,
  changeset_id         uuid references changeset,
  tag                  varchar(255),
  reason               text not null,
  status               varchar(20) not null,
  requested_by         varchar(120) not null,
  approved_by          varchar(120),
  decided_at           timestamptz,
  claimed_by           varchar(120),
  claimed_at           timestamptz,
  finished_at          timestamptz,
  result_operation_id  uuid,
  error                text,
  created_at           timestamptz not null default now(),
  -- SoD-2: the last line of defence; the API checks it first and says why.
  constraint ck_rollback_request_sod check (approved_by is null or approved_by <> requested_by),
  constraint ck_rollback_request_kind check (kind in ('CHANGESET', 'TAG'))
);
create index ix_rollback_request_status on rollback_request (workspace_id, status, created_at);
create index ix_rollback_request_operation on rollback_request (operation_id);
--rollback drop table rollback_request;
