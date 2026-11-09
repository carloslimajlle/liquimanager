--liquibase formatted sql
--
-- Role templates. Reference: docs/09-seguranca-e-rbac.md § 3.
--
-- A note on SoD-3 ("reviewers do not deploy; whoever deploys does not approve"):
-- none of the templates below combines `deploy:prd` with `exception:approve`. The
-- DBA has deploy:* and exception:approve, which LOOKS like a violation — but SoD-3
-- applies per binding within a single scope, and the API rejects with LQM-010 when
-- a concrete binding combines the two in a production scope. Granting "DBA" in a
-- PRD scope therefore requires dropping one of the two via denied_permissions.
--
-- The display names stay in pt-BR: they are shown in the interface (RNF-12).

--changeset lqm:seed-006-role-templates context:seed
insert into role_template (id, code, name, permissions)
values
  ('9269e565-a6ae-523e-b3fb-1cee345e0e57', 'ADMIN', 'Admin do cliente', array[
     'assignment:admin','workspace:admin','catalog:edit','pipeline:edit',
     'connection:edit','token:manage','audit:export','assignment:view',
     'secret:view-status'
   ]),
  ('50511462-c342-5943-9c53-02b39804d6fc', 'DBA', 'DBA', array[
     'assignment:view','deploy:*','rollback:*','rollback:approve',
     'exception:approve','drift:*','connection:edit'
   ]),
  ('3cdaa69c-048c-5719-abc3-a08119f341c4', 'DEV', 'Desenvolvedor', array[
     'assignment:view','deploy:dev,tst','exception:request','drift:run'
   ]),
  ('9b29e5b7-c6b0-5ead-b02a-00aebd901ade', 'RELEASE', 'Release Manager', array[
     'assignment:view','promote:hml,prd','exception:request'
   ]),
  ('c85ee4aa-7b27-5ce6-8e89-815a795bebd5', 'SECURITY', 'Revisor de segurança', array[
     'assignment:view','policy:edit','policy:approve','secret:view-status','audit:export'
   ]),
  ('99649b95-845e-5817-9dae-d107a0143b81', 'READER', 'Leitura', array[
     'assignment:view'
   ]),
  -- Break-glass: SoD-4 requires an approver and expires_at <= 24 h on the binding.
  ('4ba32722-995c-508f-90be-2cff32b8d8b8', 'BREAKGLASS', 'Break-glass', array[
     'rollback:prd','deploy:prd'
   ]);
--rollback delete from role_template;
