--liquibase formatted sql
--
-- Connections discovered through ingestion arrive unverified until an admin confirms
-- them (docs/06, ingestion rule 1). The spec mentions this state but docs/05 never
-- modelled it, hence this changeset.

--changeset lqm:035-db-connection-verified
alter table db_connection add column verified boolean not null default true;
--rollback alter table db_connection drop column verified;
