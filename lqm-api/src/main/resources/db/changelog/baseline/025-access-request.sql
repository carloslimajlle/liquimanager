--liquibase formatted sql
--
-- A person who signed in and has no access asks for it; whoever administers access sees the request in the bell. One open request per person.

--changeset lqm:059-access-request
create table access_request (
  id            uuid primary key,
  workspace_id  uuid not null,
  requester     varchar(200) not null,
  email         varchar(200),
  groups        text not null default '[]',
  message       text,
  status        varchar(12) not null,
  requested_at  timestamptz not null default now(),
  created_at    timestamptz not null default now(),
  resolved_at   timestamptz,
  resolved_by   varchar(120),
  resolved_note text,
  constraint ck_access_request_status check (status in ('OPEN', 'GRANTED', 'DISMISSED'))
);
create unique index ux_access_request_open on access_request (workspace_id, lower(requester)) where status = 'OPEN';
create index ix_access_request_status on access_request (workspace_id, status, requested_at);
--rollback drop table access_request;
