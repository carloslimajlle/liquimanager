--liquibase formatted sql
--
-- What a rule can say about WHEN and HOW MUCH it speaks: only from a severity up, not during quiet hours (the message waits for the
-- window to end), and grouped into one message every N minutes.

--changeset lqm:051-notification-rule-options
alter table notification_rule add column min_severity smallint;
alter table notification_rule add column quiet_start time;
alter table notification_rule add column quiet_end time;
alter table notification_rule add column quiet_zone varchar(40);
alter table notification_rule add column digest_minutes int;
alter table notification_rule add constraint ck_notification_rule_severity check (min_severity is null or min_severity between 0 and 4);
alter table notification_rule add constraint ck_notification_rule_quiet check ((quiet_start is null) = (quiet_end is null));
alter table notification_rule add constraint ck_notification_rule_digest check (digest_minutes is null or digest_minutes between 5 and 1440);
--rollback alter table notification_rule drop constraint ck_notification_rule_digest;
--rollback alter table notification_rule drop constraint ck_notification_rule_quiet;
--rollback alter table notification_rule drop constraint ck_notification_rule_severity;
--rollback alter table notification_rule drop column digest_minutes;
--rollback alter table notification_rule drop column quiet_zone;
--rollback alter table notification_rule drop column quiet_end;
--rollback alter table notification_rule drop column quiet_start;
--rollback alter table notification_rule drop column min_severity;
