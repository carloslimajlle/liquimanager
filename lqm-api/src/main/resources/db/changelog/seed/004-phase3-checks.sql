--liquibase formatted sql
--
-- The phase-3 checks the engine already implements. seed/003-checks.sql is generated from the
-- catalogue and enables only phase 1, and an applied changeset must not change, so enabling
-- these three is a changeset of its own. None is in the `default` package (column P of
-- docs/08 is empty): enabling a check only makes it available to a package.

--changeset lqm:seed-004-enable-phase3-checks context:seed
update policy_check set enabled = true
 where workspace_id is null and code in ('CreateIndexOnline', 'CursorUsageWarn', 'PiiColumnEncryption');
--rollback update policy_check set enabled = false where workspace_id is null and code in ('CreateIndexOnline', 'CursorUsageWarn', 'PiiColumnEncryption');
