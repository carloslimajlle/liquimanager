--liquibase formatted sql
--
-- RBAC, principals synchronised from the identity provider, and service tokens.
-- Reference: docs/05-modelo-de-dados.md § 2.5 and docs/09-seguranca-e-rbac.md

--changeset lqm:031-role-template
create table role_template (
  id          uuid primary key,
  code        varchar(40) unique not null,
  name        varchar(80) not null,
  permissions text[] not null
);
--rollback drop table role_template;

--changeset lqm:032-principal
-- Users and groups come from the identity provider (D-03: Keycloak).
-- external_id is the subject or group id over there.
create table principal (
  id           uuid primary key,
  type         varchar(10) not null,
  external_id  varchar(200) unique not null,
  display_name varchar(200) not null,
  email        varchar(200)
);
--rollback drop table principal;

--changeset lqm:033-role-binding
-- A binding grants a template within a scope, and may add or deny permissions.
-- A denial always beats a grant (docs/09).
create table role_binding (
  id                 uuid primary key,
  workspace_id       uuid not null,
  principal_id       uuid not null references principal,
  role_template_id   uuid references role_template,
  extra_permissions  text[],
  denied_permissions text[],
  scope_type         varchar(20) not null,
  scope_id           uuid,
  approver           varchar(120),
  expires_at         timestamptz,
  created_at         timestamptz not null default now()
);
create index ix_role_binding_principal on role_binding (principal_id);
create index ix_role_binding_scope on role_binding (workspace_id, scope_type, scope_id);
--rollback drop table role_binding;

--changeset lqm:034-service-token
-- RNF-08: only the hash (Argon2id) is stored. The plaintext token is shown once,
-- in the response of the POST that created it.
create table service_token (
  id           uuid primary key,
  workspace_id uuid not null,
  name         varchar(120) not null,
  token_hash   varchar(200) not null,
  -- visible prefix of the lqm_<prefix>_<secret> token, to identify it without revealing it
  prefix       char(8) not null,
  scopes       text[] not null,
  project_ids  uuid[],
  expires_at   timestamptz,
  last_used_at timestamptz,
  created_by   varchar(120) not null,
  revoked_at   timestamptz
);
create unique index ux_service_token_prefix on service_token (prefix);
--rollback drop table service_token;
