--liquibase formatted sql
--
-- Tables holding changesets and their last known state per database.
-- Reference: docs/05-modelo-de-dados.md § 2.2
--
-- Careful: the formatted-SQL parser treats a leading "-- changeset" (and "-- rollback",
-- "-- comment", ...) as a directive, so no prose here may open with one of those words.

--changeset lqm:010-changeset
-- A changeset's identity in Liquibase is the triple (id, author, filePath).
create table changeset (
  id            uuid primary key,
  changelog_id  uuid not null references changelog,
  cs_id         varchar(255) not null,
  author        varchar(255) not null,
  file_path     varchar(500) not null,
  checksum      varchar(64),
  labels        varchar(500),
  contexts      varchar(500),
  comments      text,
  -- true when the changeset has an explicit <rollback> or is auto-reversible
  has_rollback  boolean not null,
  is_break_fix  boolean not null default false,
  first_seen_at timestamptz not null default now(),
  unique (changelog_id, cs_id, author, file_path)
);
--rollback drop table changeset;

--changeset lqm:011-changeset-state
-- Feeds the Deployment Matrix (screen 04): one row per changeset per database.
create table changeset_state (
  changeset_id      uuid references changeset,
  db_connection_id  uuid references db_connection,
  status            varchar(20) not null,
  -- exec_type mirrors the EXECTYPE column of the target DATABASECHANGELOG
  exec_type         varchar(20),
  -- No foreign key: operation is partitioned and has a composite key (id, started_at)
  last_operation_id uuid,
  deployed_at       timestamptz,
  deployment_id     varchar(20),
  tag               varchar(255),
  primary key (changeset_id, db_connection_id)
);
create index ix_changeset_state_conn on changeset_state (db_connection_id, status);
--rollback drop table changeset_state;
