--liquibase formatted sql
--
-- What only a merge request or a branch has is "in review", grouped by the merge request. When the merge request is closed or
-- deleted nobody tells LiquiManager, so a person closes the group here, with a note. Closing is a mark on the changesets, not a delete.

--changeset lqm:057-changeset-review
alter table changeset add column origin_seen_at timestamptz;
alter table changeset add column dismissed_at timestamptz;
alter table changeset add column dismissed_by varchar(120);
alter table changeset add column dismissed_note text;
update changeset set origin_seen_at = first_seen_at where seen_on_default_at is null;
--rollback alter table changeset drop column dismissed_note;
--rollback alter table changeset drop column dismissed_by;
--rollback alter table changeset drop column dismissed_at;
--rollback alter table changeset drop column origin_seen_at;
