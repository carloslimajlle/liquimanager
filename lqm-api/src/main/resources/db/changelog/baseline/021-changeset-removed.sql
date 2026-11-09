--liquibase formatted sql
--
-- A changeset that is no longer in the changelog on the default branch. LiquiManager only ever learned changesets from reports and kept
-- them forever, so a file removed by a merge request still looked pending in the promotion dialog and in the matrix.

--changeset lqm:054-changeset-removed
alter table changeset add column removed_at timestamptz;
alter table changeset add column removed_commit varchar(80);
alter table changelog add column reconciled_at timestamptz;
--rollback alter table changelog drop column reconciled_at;
--rollback alter table changeset drop column removed_commit;
--rollback alter table changeset drop column removed_at;
