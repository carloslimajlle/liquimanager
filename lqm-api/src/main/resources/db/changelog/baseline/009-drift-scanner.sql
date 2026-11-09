--liquibase formatted sql
--
-- What drift and the scanner need beyond the tables of 005.

--changeset lqm:038-drift-item-fingerprint
-- A hash of what the item says (kind of change, object, and what changed). An accepted item is carried to later
-- runs while this stays the same, and stops being carried the moment the object changes again.
alter table drift_item add column fingerprint char(64);
create index ix_drift_item_fingerprint on drift_item (fingerprint);
--rollback drop index ix_drift_item_fingerprint;
--rollback alter table drift_item drop column fingerprint;

--changeset lqm:039-drift-run-error
alter table drift_run add column error text;
--rollback alter table drift_run drop column error;

--changeset lqm:040-scanner-state
-- Where the scanner stopped reading DATABASECHANGELOG on each connection, and how its last pass went.
create table scanner_state (
  db_connection_id uuid primary key references db_connection,
  last_seen        timestamptz,
  last_run_at      timestamptz,
  last_ok          boolean,
  last_error       text,
  stuck_lock_since timestamptz
);
--rollback drop table scanner_state;
