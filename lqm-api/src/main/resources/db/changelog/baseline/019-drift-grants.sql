--liquibase formatted sql
--
-- The grants a snapshot was taken with, kept next to it: Liquibase's snapshot does not carry them, and drift compares them as a list.

--changeset lqm:052-snapshot-grants
alter table schema_snapshot add column grants_ref varchar(500);
--rollback alter table schema_snapshot drop column grants_ref;
