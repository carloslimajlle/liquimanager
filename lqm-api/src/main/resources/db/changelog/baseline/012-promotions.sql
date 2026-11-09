--liquibase formatted sql
--
-- Promotions (RF-AUT-05): a person asked for a pipeline run that moves a release to the next environment.
-- The row says that the GitLab trigger was fired and what it answered; what the run did arrives later, as an
-- ordinary operation.

--changeset lqm:044-promotion
create table promotion (
  id                uuid primary key,
  workspace_id      uuid not null,
  pipeline_id       uuid not null,
  stage_id          uuid not null,
  environment       varchar(40) not null,
  release           varchar(120),
  status            varchar(20) not null,
  requested_by      varchar(120) not null,
  gitlab_pipeline   varchar(60),
  gitlab_url        varchar(500),
  error             varchar(300),
  created_at        timestamptz not null default now(),
  constraint ck_promotion_status check (status in ('TRIGGERED', 'FAILED'))
);
create index ix_promotion_pipeline on promotion (pipeline_id, created_at desc);
--rollback drop table promotion;
