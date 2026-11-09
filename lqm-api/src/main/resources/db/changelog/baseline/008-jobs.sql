--liquibase formatted sql
--
-- Scheduled jobs. The lock table is ShedLock's, so that two replicas of the API do not run the same job
-- at once (RNF-05). job_run is the history the screens read to say when a job last ran and how it went.

--changeset lqm:036-shedlock
create table shedlock (
  name       varchar(64) primary key,
  lock_until timestamptz not null,
  locked_at  timestamptz not null,
  locked_by  varchar(255) not null
);
--rollback drop table shedlock;

--changeset lqm:037-job-run
create table job_run (
  id         uuid primary key,
  job        varchar(60) not null,
  started_at timestamptz not null,
  ended_at   timestamptz,
  ok         boolean,
  detail     text
);
create index ix_job_run_job on job_run (job, started_at desc);
--rollback drop table job_run;
