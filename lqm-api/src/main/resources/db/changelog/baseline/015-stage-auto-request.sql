--liquibase formatted sql
--
-- A stage that requires approval can ask for it by itself: when the stage before it receives a deploy, the request is opened without
-- anybody pressing Promote. Off by default; the approval itself is still a second person's decision.

--changeset lqm:048-stage-auto-request
alter table pipeline_stage add column auto_request boolean not null default false;
--rollback alter table pipeline_stage drop column auto_request;
