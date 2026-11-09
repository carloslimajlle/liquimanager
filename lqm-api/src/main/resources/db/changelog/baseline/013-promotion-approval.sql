--liquibase formatted sql
--
-- A stage that requires approval does not fire the GitLab trigger when somebody asks to promote: the request waits for a
-- second person (separation of duties), who approves or rejects it; the requester can withdraw it.

--changeset lqm:045-promotion-approval
alter table promotion drop constraint ck_promotion_status;
alter table promotion add constraint ck_promotion_status
  check (status in ('REQUESTED', 'TRIGGERED', 'FAILED', 'REJECTED', 'CANCELED'));
alter table promotion add column decided_by varchar(120);
alter table promotion add column decided_at timestamptz;
create index ix_promotion_status on promotion (workspace_id, status, created_at desc);
--rollback drop index ix_promotion_status;
--rollback alter table promotion drop column decided_at;
--rollback alter table promotion drop column decided_by;
--rollback delete from promotion where status in ('REQUESTED', 'REJECTED', 'CANCELED');
--rollback alter table promotion drop constraint ck_promotion_status;
--rollback alter table promotion add constraint ck_promotion_status check (status in ('TRIGGERED', 'FAILED'));
