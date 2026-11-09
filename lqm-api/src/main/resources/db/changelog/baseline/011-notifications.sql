--liquibase formatted sql
--
-- Notifications (RF-INT-05): who is told about what, and what was actually sent.

--changeset lqm:042-notification-rule
create table notification_rule (
  id             uuid primary key,
  workspace_id   uuid not null,
  name           varchar(120) not null,
  event          varchar(40) not null,
  channel        varchar(20) not null,
  -- email addresses (comma separated) or a webhook URL. A webhook URL is a credential: it lets anyone post as us.
  target         varchar(1000) not null,
  environment_id uuid references environment,
  project_id     uuid references project,
  enabled        boolean not null default true,
  created_by     varchar(120) not null,
  created_at     timestamptz not null default now(),
  constraint ck_notification_rule_channel check (channel in ('EMAIL', 'TEAMS', 'SLACK'))
);
create index ix_notification_rule_event on notification_rule (workspace_id, event) where enabled;
--rollback drop table notification_rule;

--changeset lqm:043-notification-delivery
-- One row per message per rule. dedupe_key makes "tell me once" true even when the same event is seen twice.
create table notification_delivery (
  id         uuid primary key,
  rule_id    uuid not null references notification_rule on delete cascade,
  event      varchar(40) not null,
  subject    varchar(300) not null,
  body       text not null,
  dedupe_key varchar(200) not null,
  status     varchar(10) not null,
  attempts   int not null default 0,
  last_error text,
  next_at    timestamptz not null default now(),
  created_at timestamptz not null default now(),
  sent_at    timestamptz,
  unique (rule_id, dedupe_key)
);
create index ix_notification_delivery_due on notification_delivery (status, next_at);
--rollback drop table notification_delivery;
