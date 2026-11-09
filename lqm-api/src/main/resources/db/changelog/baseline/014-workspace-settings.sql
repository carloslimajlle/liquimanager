--liquibase formatted sql
--
-- What a workspace administrator can tune: how long the housekeeping jobs keep what they own, and how long a promotion request
-- may wait for its approver. Operations and the audit trail are not here on purpose: they are kept for the legal period (D-08).

--changeset lqm:046-workspace-settings
alter table workspace add column job_run_retention_days int not null default 90 check (job_run_retention_days between 7 and 3650);
alter table workspace add column notification_retention_days int not null default 180 check (notification_retention_days between 7 and 3650);
alter table workspace add column promotion_ttl_hours int not null default 72 check (promotion_ttl_hours between 0 and 8760);
--rollback alter table workspace drop column promotion_ttl_hours;
--rollback alter table workspace drop column notification_retention_days;
--rollback alter table workspace drop column job_run_retention_days;

--changeset lqm:047-promotion-expired
alter table promotion drop constraint ck_promotion_status;
alter table promotion add constraint ck_promotion_status
  check (status in ('REQUESTED', 'TRIGGERED', 'FAILED', 'REJECTED', 'CANCELED', 'EXPIRED'));
--rollback update promotion set status = 'CANCELED' where status = 'EXPIRED';
--rollback alter table promotion drop constraint ck_promotion_status;
--rollback alter table promotion add constraint ck_promotion_status check (status in ('REQUESTED', 'TRIGGERED', 'FAILED', 'REJECTED', 'CANCELED'));
