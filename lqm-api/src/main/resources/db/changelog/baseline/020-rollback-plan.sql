--liquibase formatted sql
--
-- Going back to a changeset undoes it and everything applied after it. What that is gets frozen in the request, so the person who
-- approves, and the runner that runs it, deal with the list that was shown and not with whatever the database holds later.

--changeset lqm:053-rollback-plan
alter table rollback_request add column plan text;
alter table rollback_request add column plan_operation_id uuid;
--rollback alter table rollback_request drop column plan_operation_id;
--rollback alter table rollback_request drop column plan;
