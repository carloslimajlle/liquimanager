--liquibase formatted sql
--
-- Policy engine: check catalogue, packages, findings and exceptions.
-- Reference: docs/05-modelo-de-dados.md § 2.4 and docs/08-motor-de-politicas.md

--changeset lqm:017-policy-check
-- workspace_id null = prebuilt check, visible to every workspace.
-- Severity 0..4; blocking compares it against the package threshold.
create table policy_check (
  id                uuid primary key,
  workspace_id      uuid,
  code              varchar(80) not null,
  short_name        varchar(120) not null,
  description       text,
  category          varchar(60) not null,
  scope             varchar(20) not null,
  changelog_types   varchar(60) not null default 'XML,SQL,YAML,JSON',
  kind              varchar(20) not null,
  default_severity  smallint not null check (default_severity between 0 and 4),
  parameters_schema jsonb not null,
  parameters        jsonb not null default '{}',
  message_template  text not null,
  enabled           boolean not null default true,
  -- a customised copy of a builtin (POST /policy-checks/{id}/copy)
  parent_id         uuid references policy_check,
  updated_by        varchar(120),
  updated_at        timestamptz,
  unique (workspace_id, code)
);
--rollback drop table policy_check;

--changeset lqm:018-policy-chain-member
create table policy_chain_member (
  chain_id  uuid references policy_check,
  member_id uuid references policy_check,
  primary key (chain_id, member_id)
);
--rollback drop table policy_chain_member;

--changeset lqm:019-policy-package
create table policy_package (
  id                      uuid primary key,
  workspace_id            uuid not null,
  name                    varchar(120) not null,
  description             text,
  block_threshold_prod    smallint not null default 3,
  block_threshold_nonprod smallint not null default 4,
  unique (workspace_id, name)
);
--rollback drop table policy_package;

--changeset lqm:020-policy-package-check
create table policy_package_check (
  package_id          uuid references policy_package,
  check_id            uuid references policy_check,
  severity_override   smallint,
  parameters_override jsonb,
  primary key (package_id, check_id)
);
--rollback drop table policy_package_check;

--changeset lqm:021-fk-policy-package
-- Closes the foreign keys left pending in 001: changelog and pipeline_stage point
-- at policy_package, which only came into existence now.
alter table changelog
  add constraint fk_changelog_policy_package
  foreign key (policy_package_id) references policy_package;
alter table pipeline_stage
  add constraint fk_pipeline_stage_policy_package
  foreign key (policy_package_id) references policy_package;
--rollback alter table pipeline_stage drop constraint fk_pipeline_stage_policy_package;
--rollback alter table changelog drop constraint fk_changelog_policy_package;

--changeset lqm:022-policy-finding
create table policy_finding (
  id                   uuid primary key,
  operation_id         uuid not null,
  operation_started_at timestamptz not null,
  check_id             uuid not null,
  changeset_id         uuid,
  severity             smallint not null,
  result               varchar(10) not null,
  message              text not null,
  object_name          varchar(255),
  line                 int,
  exception_id         uuid,
  created_at           timestamptz not null
);
create index ix_policy_finding_operation on policy_finding (operation_id);
create index ix_policy_finding_check on policy_finding (check_id, result);
--rollback drop table policy_finding;

--changeset lqm:023-policy-exception
-- Separation of duties: the approver cannot be the requester (docs/09).
-- The constraint below is the last line of defence; the rule also lives in the API.
create table policy_exception (
  id             uuid primary key,
  workspace_id   uuid not null,
  check_id       uuid not null,
  changeset_id   uuid,
  environment_id uuid,
  project_id     uuid,
  justification  text not null,
  requested_by   varchar(120) not null,
  approved_by    varchar(120),
  status         varchar(20) not null,
  -- every exception expires: there is no such thing as a permanent one
  expires_at     timestamptz not null,
  created_at     timestamptz not null default now(),
  constraint ck_policy_exception_sod check (approved_by is null or approved_by <> requested_by)
);
create index ix_policy_exception_status on policy_exception (status, expires_at);
--rollback drop table policy_exception;
