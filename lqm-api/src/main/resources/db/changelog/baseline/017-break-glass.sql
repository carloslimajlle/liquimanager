--liquibase formatted sql
--
-- The local emergency account (docs/09 § 1): used when the identity provider is down. One row of state (failed attempts, lockout, the
-- last TOTP step accepted so a code works once) and the sessions it opened, kept as hashes like every token here.

--changeset lqm:050-break-glass
create table break_glass_state (
    id              int primary key check (id = 1),
    failed_attempts int not null default 0,
    locked_until    timestamptz,
    last_totp_step  bigint not null default 0
);
create table break_glass_session (
    id          uuid primary key,
    token_hash  varchar(64) not null unique,
    created_at  timestamptz not null default now(),
    expires_at  timestamptz not null,
    revoked_at  timestamptz,
    client_ip   varchar(64)
);
--rollback drop table break_glass_session;
--rollback drop table break_glass_state;
