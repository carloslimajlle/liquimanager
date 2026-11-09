--liquibase formatted sql

--changeset lqm:seed-005-workspace-name context:seed
-- The default workspace was first seeded with the name of the organisation the product started in.
update workspace set name = 'LiquiManager' where slug = 'default' and name = 'CONNECT4';
--rollback update workspace set name = 'CONNECT4' where slug = 'default' and name = 'LiquiManager';
