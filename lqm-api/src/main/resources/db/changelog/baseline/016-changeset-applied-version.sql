--liquibase formatted sql
--
-- What was applied, not what the file says today: the checksum a database holds for a changeset and the commit it was applied from.
-- With them, a changeset edited after it ran is told apart from one that just has not run yet (the check ChecksumChanged), and the screen
-- can show the applied version against the current one.

--changeset lqm:049-changeset-state-applied-version
alter table changeset_state add column applied_checksum varchar(64);
alter table changeset_state add column applied_commit varchar(64);
--rollback alter table changeset_state drop column applied_commit;
--rollback alter table changeset_state drop column applied_checksum;
