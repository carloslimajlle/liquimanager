--liquibase formatted sql
--
-- Initial workspace and default environments.
-- Reference: docs/05 § 3 and D-15 (DEV/TST/HML/PRD, configurable).
--
-- The uuids are uuid5 values from a fixed namespace (fixed
-- namespace): running the seed in another environment
-- produces the same ids, which makes comparing databases easy.

--changeset lqm:seed-001-workspace context:seed
-- ai_enabled stays false: D-13 left AI analysis as a future feature.
insert into workspace (id, slug, name, ai_enabled)
values ('a5003659-317a-589a-9a01-2c9854fc6d72', 'default', 'CONNECT4', false);
--rollback delete from workspace where slug = 'default';

--changeset lqm:seed-002-environments context:seed
-- color_token matches the env-* classes of the design system (docs/10).
-- HML and PRD require approval; PRD is the only is_production, which turns on the
-- separation-of-duties rules and the stricter blocking.
insert into environment (id, workspace_id, code, name, sort_order, color_token,
                         is_production, requires_approval)
values
  ('4fded7fc-34b9-592f-8ffd-915998dc188b', 'a5003659-317a-589a-9a01-2c9854fc6d72',
   'DEV', 'Desenvolvimento', 1, 'env-dev', false, false),
  ('dc01401f-2b4f-58b0-b8a9-470ce9b41119', 'a5003659-317a-589a-9a01-2c9854fc6d72',
   'TST', 'Teste',           2, 'env-tst', false, false),
  ('ff7d4201-5b09-58f9-82fd-5967db7d9ffb', 'a5003659-317a-589a-9a01-2c9854fc6d72',
   'HML', 'Homologação',     3, 'env-hml', false, true),
  ('a8cf9c3d-760a-5541-a931-c9c9b1b7033f', 'a5003659-317a-589a-9a01-2c9854fc6d72',
   'PRD', 'Produção',        4, 'env-prd', true,  true);
--rollback delete from environment where workspace_id = 'a5003659-317a-589a-9a01-2c9854fc6d72';
