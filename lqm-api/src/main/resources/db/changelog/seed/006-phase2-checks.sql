--liquibase formatted sql
--
-- The phase-2 checks the engine now implements. seed/003-checks.sql is generated from the catalogue and enables only
-- phase 1, and an applied changeset must not change, so enabling these is a changeset of its own.
--
-- Column P of docs/08 puts nine of them in the `default` package; the rest are only made available to a package. The
-- package id is the one 003 derives (uuid5), which the rollback below does not need to look up.

--changeset lqm:seed-006-enable-phase2-checks context:seed
update policy_check set enabled = true
 where workspace_id is null and code in (
   'RunAlwaysForbidden', 'RunOnChangeReview', 'ChangesetStandards', 'ModifyDataTypeNarrowing', 'UniqueIndexRequiresDedup',
   'AlterRoleMembership', 'IdentityVsSequence', 'ReservedWordAsIdentifier', 'CrossDatabaseReference', 'CollationExplicit',
   'TableCommentRequired', 'ColumnCommentRequired', 'PiiColumnClassification', 'SeedDataWithPii', 'LoadDataFileSize');
insert into policy_package_check (package_id, check_id)
select '18e3a699-a53d-559a-ad06-074c7051d818', pc.id from policy_check pc
 where pc.workspace_id is null and pc.code in (
   'ChangesetStandards', 'ModifyDataTypeNarrowing', 'UniqueIndexRequiresDedup', 'AlterRoleMembership',
   'ReservedWordAsIdentifier', 'CrossDatabaseReference', 'TableCommentRequired', 'PiiColumnClassification', 'SeedDataWithPii')
on conflict do nothing;
--rollback delete from policy_package_check where package_id = '18e3a699-a53d-559a-ad06-074c7051d818' and check_id in (select id from policy_check where workspace_id is null and code in ('ChangesetStandards', 'ModifyDataTypeNarrowing', 'UniqueIndexRequiresDedup', 'AlterRoleMembership', 'ReservedWordAsIdentifier', 'CrossDatabaseReference', 'TableCommentRequired', 'PiiColumnClassification', 'SeedDataWithPii'));
--rollback update policy_check set enabled = false where workspace_id is null and code in ('RunAlwaysForbidden', 'RunOnChangeReview', 'ChangesetStandards', 'ModifyDataTypeNarrowing', 'UniqueIndexRequiresDedup', 'AlterRoleMembership', 'IdentityVsSequence', 'ReservedWordAsIdentifier', 'CrossDatabaseReference', 'CollationExplicit', 'TableCommentRequired', 'ColumnCommentRequired', 'PiiColumnClassification', 'SeedDataWithPii', 'LoadDataFileSize');
