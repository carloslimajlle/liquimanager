--liquibase formatted sql
--
-- Where a changeset has been seen. A report from a merge request's branch registers what is in that branch, so a changeset can exist here
-- without ever having been on the default branch (a rename or an addition still under review). Only what was seen on the default branch
-- is part of the project's changelog; the rest is shown as "only in" the branch or merge request it came from.

--changeset lqm:055-changeset-origin
alter table changeset add column seen_on_default_at timestamptz;
alter table changeset add column origin_branch varchar(255);
alter table changeset add column origin_mr_iid varchar(40);
alter table changeset add column origin_mr_title varchar(300);
alter table changeset add column origin_mr_url varchar(500);
update changeset set seen_on_default_at = first_seen_at
 where exists (select 1
                 from operation_changeset oc
                 join operation o on o.id = oc.operation_id and o.started_at = oc.operation_started_at
                 join changelog cl on cl.id = changeset.changelog_id
                 join project p on p.id = cl.project_id
                where oc.changeset_id = changeset.id
                  and (o.git_branch is null or o.git_branch = '' or o.git_branch = p.default_branch));
-- from now on a row nobody says anything about counts as seen: only a report from another branch registers one as "not yet on the default branch"
alter table changeset alter column seen_on_default_at set default now();
--rollback alter table changeset drop column origin_mr_url;
--rollback alter table changeset drop column origin_mr_title;
--rollback alter table changeset drop column origin_mr_iid;
--rollback alter table changeset drop column origin_branch;
--rollback alter table changeset drop column seen_on_default_at;

--changeset lqm:056-clear-removed-never-seen
-- Before changesets learned where they were seen, a changeset only a merge request had was marked removed by the next report from the default branch.
update changeset set removed_at = null, removed_commit = null where seen_on_default_at is null and removed_at is not null;
--rollback select 1;
