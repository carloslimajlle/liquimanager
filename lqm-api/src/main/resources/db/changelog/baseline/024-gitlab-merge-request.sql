--liquibase formatted sql
--
-- What GitLab last told us about each merge request (webhook). A merge request can be closed seconds after it is opened, before its pipeline
-- has reported: the webhook then finds no changesets to close. The state is kept so that when that pipeline reports, a report from a merge
-- request already known to be closed is born closed instead of listed as in review.

--changeset lqm:058-gitlab-merge-request
create table gitlab_merge_request (
  project_id uuid not null references project,
  iid        varchar(40) not null,
  state      varchar(20) not null,
  changed_by varchar(120),
  changed_at timestamptz not null default now(),
  primary key (project_id, iid)
);
--rollback drop table gitlab_merge_request;
