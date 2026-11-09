package io.github.carloslimajlle.liquimanager.ingest;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.carloslimajlle.liquimanager.common.Labels;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;
import io.github.carloslimajlle.liquimanager.security.ServiceToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives an executed Liquibase operation and turns it into rows (docs/06, ingestion).
 *
 * <p>Idempotent on {@code operationId}: a collector may resend the same operation
 * (it sends once at start with RUNNING and again at the end), and the second call
 * updates in place instead of duplicating anything.
 *
 * <p>Everything runs in one transaction, and the {@link OperationIngested} event is
 * only delivered after commit, so the live feed never announces an operation the
 * database does not hold.
 */
@Service
public class IngestService {

    private static final Logger LOG = LoggerFactory.getLogger(IngestService.class);
    /** Execution logs are truncated at 2 MB (docs/06). */
    static final int MAX_BLOB_CHARS = 2 * 1024 * 1024;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final BlobStore blobs;
    private final ApplicationEventPublisher events;

    public IngestService(JdbcClient jdbc, BlobStore blobs, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.blobs = blobs;
        this.events = events;
    }

    /** {@code created} is false when the operation had already been received. */
    public record Result(UUID operationId, boolean created) {
    }

    private record ResolvedConnection(UUID id, String slug, String environment) {
    }

    @Transactional
    public Result ingest(ServiceToken token, IngestOperation in) {
        validate(in);

        UUID workspaceId = token.workspaceId();
        UUID projectId = resolveProject(workspaceId, token, in.project());
        ResolvedConnection connection = resolveConnection(workspaceId, in);
        UUID changelogId = resolveChangelog(projectId, in);

        String type = CommandCatalog.typeOf(in.command());
        boolean created = !operationExists(in);

        upsertOperation(workspaceId, projectId, changelogId, connection.id(), type, in);

        Map<String, UUID> changesetIds = new HashMap<>();
        java.util.Set<UUID> inChangelog = new java.util.HashSet<>();
        boolean onDefault = onDefaultBranch(projectId, in);
        boolean breakFix = false;
        replaceOperationChangesets(in.operationId());
        for (IngestOperation.Changeset cs : orEmpty(in.changesets())) {
            UUID changesetId = upsertChangeset(changelogId, cs, onDefault ? in.startedAt() : null);
            inChangelog.add(changesetId);
            noteWhereSeen(projectId, changesetId, onDefault, in);
            changesetIds.putIfAbsent(cs.id(), changesetId);
            breakFix |= isBreakFix(cs.labels());
            insertOperationChangeset(in, changesetId, cs);
            if ("database-change".equals(type)) {
                applyState(connection.id(), changesetId, in, cs);
            }
        }

        replaceFindings(workspaceId, in, changesetIds);
        reconcileRemoved(projectId, changelogId, in, inChangelog);

        events.publishEvent(new OperationIngested(
                in.operationId(), in.command(), in.status(), type, in.source(), in.executedBy(),
                in.project(), connection.slug(), connection.environment(), releaseOf(in),
                in.startedAt(), durationOf(in), breakFix || isBreakFix(in.labels()), workspaceId, connection.id()));

        return new Result(in.operationId(), created);
    }

    // ------------------------------------------------------------ validation

    private static void validate(IngestOperation in) {
        if (in.operationId() == null) {
            throw LqmException.invalid("operationId is required");
        }
        if (in.command() == null || !CommandCatalog.knows(in.command())) {
            throw LqmException.invalid("unknown command: " + in.command());
        }
        if (in.status() == null || !CommandCatalog.STATUSES.contains(in.status())) {
            throw LqmException.invalid("unknown status: " + in.status());
        }
        if (in.source() == null || !CommandCatalog.SOURCES.contains(in.source())) {
            throw LqmException.invalid("unknown source: " + in.source());
        }
        if (in.executedBy() == null || in.executedBy().isBlank()) {
            throw LqmException.invalid("executedBy is required");
        }
        if (in.startedAt() == null) {
            throw LqmException.invalid("startedAt is required");
        }
        if (in.connection() == null || in.connection().jdbcUrl() == null || in.connection().jdbcUrl().isBlank()) {
            throw LqmException.invalid("connection.jdbcUrl is required");
        }
        // RNF-06: a JDBC URL carrying credentials is refused outright, not stored.
        if (JdbcFingerprint.hasCredentials(in.connection().jdbcUrl())) {
            throw LqmException.invalid("connection.jdbcUrl must not carry credentials");
        }
        for (IngestOperation.Changeset cs : orEmpty(in.changesets())) {
            if (cs.id() == null || cs.author() == null || cs.filePath() == null) {
                throw LqmException.invalid("every changeset needs id, author and filePath");
            }
            if (cs.outcome() == null || !CommandCatalog.OUTCOMES.contains(cs.outcome())) {
                throw LqmException.invalid("unknown changeset outcome: " + cs.outcome());
            }
        }
        if (!orEmpty(in.changesets()).isEmpty()
                && (in.changelog() == null || in.changelog().path() == null || in.project() == null)) {
            throw LqmException.invalid("changesets need both project and changelog.path");
        }
    }

    // ------------------------------------------------------------ resolution

    private UUID resolveProject(UUID workspaceId, ServiceToken token, String slug) {
        if (slug == null || slug.isBlank()) {
            return null;
        }
        UUID id = jdbc.sql("select id from project where workspace_id = :ws and slug = :slug")
                .param("ws", workspaceId).param("slug", slug)
                .query(UUID.class).optional().orElse(null);
        if (id == null) {
            id = UuidV7.next();
            jdbc.sql("insert into project (id, workspace_id, slug, name) values (:id, :ws, :slug, :slug)")
                    .param("id", id).param("ws", workspaceId).param("slug", slug).update();
        }
        if (!token.projectIds().isEmpty() && !token.projectIds().contains(id)) {
            throw LqmException.forbidden("this service token is not allowed to report on project " + slug);
        }
        return id;
    }

    private ResolvedConnection resolveConnection(UUID workspaceId, IngestOperation in) {
        IngestOperation.Connection c = in.connection();
        String fingerprint = JdbcFingerprint.of(c.jdbcUrl());

        Optional<ResolvedConnection> found = Optional.empty();
        if (c.key() != null && !c.key().isBlank()) {
            found = findConnection("c.slug = :key", workspaceId, "key", c.key());
        }
        if (found.isEmpty() && fingerprint != null) {
            found = findConnection("c.host_fingerprint = :fp", workspaceId, "fp", fingerprint);
        }
        if (found.isPresent()) {
            if (c.dbVersion() != null) {
                jdbc.sql("update db_connection set db_version = :v where id = :id")
                        .param("v", c.dbVersion()).param("id", found.get().id()).update();
            }
            return found.get();
        }
        return registerConnection(workspaceId, in, fingerprint);
    }

    private Optional<ResolvedConnection> findConnection(String predicate, UUID workspaceId, String name, String value) {
        return jdbc.sql("""
                select c.id, c.slug, e.code
                  from db_connection c join environment e on e.id = c.environment_id
                 where c.workspace_id = :ws and c.archived_at is null and %s
                """.formatted(predicate))
                .param("ws", workspaceId).param(name, value)
                .query((rs, n) -> new ResolvedConnection(rs.getObject("id", UUID.class),
                        rs.getString("slug"), rs.getString("code")))
                .optional();
    }

    /** Rule 1: an unknown connection is created as "unverified", pending an admin's confirmation. */
    private ResolvedConnection registerConnection(UUID workspaceId, IngestOperation in, String fingerprint) {
        if (in.environment() == null || in.environment().isBlank()) {
            throw LqmException.invalid("environment is required to register a new connection");
        }
        UUID environmentId = jdbc.sql("select id from environment where workspace_id = :ws and code = :code")
                .param("ws", workspaceId).param("code", in.environment().toUpperCase(Locale.ROOT))
                .query(UUID.class).optional()
                .orElseThrow(() -> LqmException.invalid("unknown environment: " + in.environment()));

        IngestOperation.Connection c = in.connection();
        String basis = c.key() != null && !c.key().isBlank() ? c.key() : String.valueOf(fingerprint);
        String slug = slugify(basis);
        UUID id = UuidV7.next();
        jdbc.sql("""
                insert into db_connection (id, workspace_id, environment_id, slug, display_name, engine,
                                           jdbc_url, host_fingerprint, db_version, verified)
                values (:id, :ws, :env, :slug, :slug, :engine, :url, :fp, :version, false)
                """)
                .param("id", id).param("ws", workspaceId).param("env", environmentId)
                .param("slug", slug).param("engine", engineOf(c))
                .param("url", c.jdbcUrl()).param("fp", fingerprint == null ? slug : fingerprint)
                .param("version", c.dbVersion())
                .update();
        LOG.info("registered unverified connection {} ({}) from ingestion", slug, fingerprint);
        return new ResolvedConnection(id, slug, in.environment().toUpperCase(Locale.ROOT));
    }

    private UUID resolveChangelog(UUID projectId, IngestOperation in) {
        if (in.changelog() == null || in.changelog().path() == null || projectId == null) {
            return null;
        }
        String path = in.changelog().path();
        return jdbc.sql("select id from changelog where project_id = :p and path = :path")
                .param("p", projectId).param("path", path)
                .query(UUID.class).optional()
                .orElseGet(() -> {
                    UUID id = UuidV7.next();
                    // RF-CAD-03 CA1: discovered through ingestion, so it starts unverified.
                    jdbc.sql("""
                            insert into changelog (id, project_id, name, path, format, verified)
                            values (:id, :p, :name, :path, :format, false)
                            """)
                            .param("id", id).param("p", projectId).param("name", nameOf(path))
                            .param("path", path).param("format", formatOf(in.changelog()))
                            .update();
                    return id;
                });
    }

    // ------------------------------------------------------------- operation

    private boolean operationExists(IngestOperation in) {
        return jdbc.sql("select count(*) from operation where id = :id and started_at = :at")
                .param("id", in.operationId()).param("at", ts(in.startedAt()))
                .query(Long.class).single() > 0;
    }

    private void upsertOperation(UUID workspaceId, UUID projectId, UUID changelogId, UUID connectionId,
                                 String type, IngestOperation in) {
        String logRef = in.log() == null ? null
                : blobs.put("operations/" + in.operationId() + "/log.txt", truncate(in.log()));
        String sqlRef = in.sql() == null ? null
                : blobs.put("operations/" + in.operationId() + "/sql.sql", truncate(in.sql()));

        IngestOperation.Summary s = in.summary();
        int[] counts = counts(in);
        IngestOperation.Runtime rt = in.runtime();
        IngestOperation.Git git = in.git();
        IngestOperation.Ci ci = in.ci();

        jdbc.sql("""
                insert into operation (id, workspace_id, project_id, changelog_id, db_connection_id, command,
                    type, status, source, executed_by, started_at, ended_at, duration_ms, deployment_id,
                    liquibase_version, collector, collector_version, contexts, labels, tag, git_commit,
                    git_branch, ci_pipeline_id, ci_job_url, host, exit_code, count_executed,
                    count_previously_run, count_filtered, count_failed, error_message, log_ref, sql_ref,
                    gate_result, raw, parent_operation_id)
                values (:id, :ws, :project, :changelog, :conn, :command, :type, :status, :source, :by,
                    :started, :ended, :duration, :deployment, :lbVersion, :collector, :collectorVersion,
                    :contexts, :labels, :tag, :commit, :branch, :ciPipeline, :ciJob, :host, :exit,
                    :cExecuted, :cPrevious, :cFiltered, :cFailed, :error, :logRef, :sqlRef, :gate,
                    cast(:raw as jsonb), :parent)
                on conflict (id, started_at) do update set
                    status = excluded.status, ended_at = excluded.ended_at, duration_ms = excluded.duration_ms,
                    deployment_id = excluded.deployment_id, liquibase_version = excluded.liquibase_version,
                    collector = excluded.collector, collector_version = excluded.collector_version,
                    contexts = excluded.contexts, labels = excluded.labels, tag = excluded.tag,
                    git_commit = excluded.git_commit, git_branch = excluded.git_branch,
                    ci_pipeline_id = excluded.ci_pipeline_id, ci_job_url = excluded.ci_job_url,
                    host = excluded.host, exit_code = excluded.exit_code,
                    count_executed = excluded.count_executed, count_previously_run = excluded.count_previously_run,
                    count_filtered = excluded.count_filtered, count_failed = excluded.count_failed,
                    error_message = excluded.error_message,
                    log_ref = coalesce(excluded.log_ref, operation.log_ref),
                    sql_ref = coalesce(excluded.sql_ref, operation.sql_ref),
                    gate_result = excluded.gate_result, raw = excluded.raw,
                    parent_operation_id = coalesce(excluded.parent_operation_id, operation.parent_operation_id)
                """)
                .param("id", in.operationId()).param("ws", workspaceId).param("parent", in.parentOperationId())
                .param("project", projectId).param("changelog", changelogId).param("conn", connectionId)
                .param("command", in.command()).param("type", type).param("status", in.status())
                .param("source", in.source()).param("by", in.executedBy())
                .param("started", ts(in.startedAt())).param("ended", ts(in.endedAt()))
                .param("duration", durationOf(in)).param("deployment", in.deploymentId())
                .param("lbVersion", rt == null ? null : rt.liquibaseVersion())
                .param("collector", rt == null ? null : rt.collector())
                .param("collectorVersion", rt == null ? null : rt.collectorVersion())
                .param("contexts", in.contexts()).param("labels", in.labels()).param("tag", in.tag())
                .param("commit", git == null ? null : git.commit())
                .param("branch", git == null ? null : git.branch())
                .param("ciPipeline", ci == null ? null : ci.pipelineId())
                .param("ciJob", ci == null ? null : ci.jobUrl())
                .param("host", rt == null ? null : rt.host())
                .param("exit", in.error() == null ? null : in.error().exitCode())
                .param("cExecuted", counts[0]).param("cPrevious", counts[1])
                .param("cFiltered", counts[2]).param("cFailed", counts[3])
                .param("error", in.error() == null ? null : in.error().message())
                .param("logRef", logRef).param("sqlRef", sqlRef)
                .param("gate", in.policy() == null ? null : in.policy().gateResult())
                .param("raw", JSON.writeValueAsString(in.withoutBlobs()))
                .update();
        if (s == null && in.changesets() == null) {
            LOG.debug("operation {} arrived with neither summary nor changesets", in.operationId());
        }
    }

    // ------------------------------------------------------------ changesets

    /** {@code seenOnDefaultAt}: when this report is from the default branch, its start; null from any other branch (the row is born "not there yet"). */
    private UUID upsertChangeset(UUID changelogId, IngestOperation.Changeset cs, Instant seenOnDefaultAt) {
        return jdbc.sql("""
                insert into changeset (id, changelog_id, cs_id, author, file_path, checksum, labels,
                                       contexts, has_rollback, is_break_fix, seen_on_default_at)
                values (:id, :cl, :cs, :author, :path, :checksum, :labels, :contexts, :rb, :bf, :seen)
                on conflict (changelog_id, cs_id, author, file_path) do update set
                    checksum = coalesce(excluded.checksum, changeset.checksum),
                    labels = excluded.labels, contexts = excluded.contexts,
                    has_rollback = excluded.has_rollback, is_break_fix = excluded.is_break_fix
                returning id
                """)
                .param("id", UuidV7.next()).param("cl", changelogId).param("cs", cs.id())
                .param("author", cs.author()).param("path", cs.filePath()).param("checksum", cs.checksum())
                .param("labels", cs.labels()).param("contexts", cs.contexts())
                .param("rb", Boolean.TRUE.equals(cs.hasRollback())).param("bf", isBreakFix(cs.labels()))
                .param("seen", ts(seenOnDefaultAt)).query(UUID.class).single();
    }

    /**
     * Notices the changesets that are no longer in the changelog. A report whose changelog is COMPLETE (the collector classified all of
     * it) is a photo of the changelog at one commit; what LiquiManager knows and the photo lacks was removed, and what comes back is
     * not removed anymore.
     *
     * <p>Only a photo that can be trusted counts: of the project's default branch (a merge request's branch lacks what main has, or has
     * what main does not yet), not from a rollback (which runs at an OLD commit), and not older than the last one used.
     */
    private void reconcileRemoved(UUID projectId, UUID changelogId, IngestOperation in, java.util.Set<UUID> inChangelog) {
        if (in.changelog() == null || !Boolean.TRUE.equals(in.changelog().complete()) || inChangelog.isEmpty()
                || in.parentOperationId() != null || in.git() == null || in.git().branch() == null || in.git().branch().isBlank()) {
            return;
        }
        String defaultBranch = jdbc.sql("select default_branch from project where id = :id").param("id", projectId)
                .query(String.class).optional().orElse(null);
        if (defaultBranch == null || !defaultBranch.equals(in.git().branch())) {
            return;
        }
        // Claims the changelog for this photo: an older one that arrives late finds it already taken and leaves it alone.
        int claimed = jdbc.sql("""
                update changelog set reconciled_at = :at where id = :id and (reconciled_at is null or reconciled_at < :at)
                """).param("at", ts(in.startedAt())).param("id", changelogId).update();
        if (claimed == 0) {
            return;
        }
        List<UUID> present = new java.util.ArrayList<>(inChangelog);
        jdbc.sql("""
                update changeset set removed_at = now(), removed_commit = :commit
                 where changelog_id = :cl and removed_at is null and seen_on_default_at is not null and id not in (:present)
                """).param("commit", in.git().commit()).param("cl", changelogId).param("present", present).update();
        jdbc.sql("""
                update changeset set removed_at = null, removed_commit = null
                 where changelog_id = :cl and removed_at is not null and id in (:present)
                """).param("cl", changelogId).param("present", present).update();
    }

    /**
     * Whether the report speaks for the project's changelog: from its default branch, or from somewhere that does not say (the starter,
     * the runner, an older collector). A report from any other branch is a merge request or a feature branch still under review.
     */
    private boolean onDefaultBranch(UUID projectId, IngestOperation in) {
        if (in.git() == null || in.git().branch() == null || in.git().branch().isBlank()) {
            return true;
        }
        String defaultBranch = jdbc.sql("select default_branch from project where id = :id").param("id", projectId)
                .query(String.class).optional().orElse(null);
        return defaultBranch == null || defaultBranch.isBlank() || defaultBranch.equals(in.git().branch());
    }

    /**
     * Remembers where a changeset has been seen. Seen on the default branch it is part of the changelog for good (until a complete
     * report from there lacks it); seen only elsewhere it is "in" that branch or merge request, which is kept to say so.
     */
    private void noteWhereSeen(UUID projectId, UUID changesetId, boolean onDefault, IngestOperation in) {
        if (onDefault) {
            jdbc.sql("""
                    update changeset set seen_on_default_at = coalesce(seen_on_default_at, :at),
                           origin_branch = null, origin_mr_iid = null, origin_mr_title = null, origin_mr_url = null, origin_seen_at = null,
                           dismissed_at = null, dismissed_by = null, dismissed_note = null
                     where id = :id
                    """).param("at", ts(in.startedAt())).param("id", changesetId).update();
            return;
        }
        IngestOperation.Ci ci = in.ci();
        jdbc.sql("""
                update changeset set origin_branch = :branch, origin_mr_iid = :iid, origin_mr_title = :title, origin_mr_url = :url,
                       origin_seen_at = :at, dismissed_at = null, dismissed_by = null, dismissed_note = null
                 where id = :id and seen_on_default_at is null
                """).param("at", ts(in.startedAt())).param("branch", in.git().branch()).param("iid", ci == null ? null : ci.mergeRequestIid())
                .param("title", ci == null || ci.mergeRequestTitle() == null ? null : ci.mergeRequestTitle().substring(0, Math.min(300, ci.mergeRequestTitle().length())))
                .param("url", ci == null ? null : ci.mergeRequestUrl()).param("id", changesetId).update();
        // A report from a merge request GitLab already said is closed (it was closed before this pipeline reported): born closed.
        if (ci != null && ci.mergeRequestIid() != null && !ci.mergeRequestIid().isBlank()) {
            jdbc.sql("""
                    update changeset set dismissed_at = now(), dismissed_by = m.changed_by,
                           dismissed_note = 'Encerrado automaticamente: o MR !' || m.iid || ' ja estava fechado no GitLab quando o pipeline reportou.'
                      from gitlab_merge_request m
                     where changeset.id = :id and changeset.seen_on_default_at is null
                       and m.project_id = :project and m.iid = :iid and m.state = 'closed'
                    """).param("id", changesetId).param("project", projectId).param("iid", ci.mergeRequestIid()).update();
        }
    }

    private void replaceOperationChangesets(UUID operationId) {
        jdbc.sql("delete from operation_changeset where operation_id = :id").param("id", operationId).update();
    }

    private void insertOperationChangeset(IngestOperation in, UUID changesetId, IngestOperation.Changeset cs) {
        jdbc.sql("""
                insert into operation_changeset (operation_id, operation_started_at, changeset_id, outcome,
                    filter_reason, exec_order, started_at, duration_ms, error_message)
                values (:op, :at, :cs, :outcome, :reason, :ord, :started, :duration, :error)
                on conflict (operation_id, changeset_id) do nothing
                """)
                .param("op", in.operationId()).param("at", ts(in.startedAt())).param("cs", changesetId)
                .param("outcome", cs.outcome()).param("reason", cs.filterReason()).param("ord", cs.order())
                .param("started", ts(cs.startedAt())).param("duration", cs.durationMs())
                .param("error", cs.error())
                .update();
    }

    /**
     * Moves the changeset's last known state in one database (feeds the Deployment
     * Matrix). Only outcomes that PROVE something overwrite; "filtered" and "not
     * executed" never downgrade a state we already know.
     */
    private void applyState(UUID connectionId, UUID changesetId, IngestOperation in, IngestOperation.Changeset cs) {
        String status;
        boolean overwrite = true;
        switch (cs.outcome()) {
            case "EXECUTED", "PREVIOUSLY_RUN" -> status = "EXECUTED";
            case "FAILED" -> status = "FAILED";
            case "ROLLED_BACK" -> status = "ROLLED_BACK";
            case "FILTERED" -> {
                status = "NOT_TARGETED";
                overwrite = false;
            }
            default -> {
                status = "PENDING";
                overwrite = false;
            }
        }
        Instant deployedAt = "EXECUTED".equals(cs.outcome())
                ? (cs.startedAt() != null ? cs.startedAt() : in.startedAt()) : null;

        String conflict = overwrite ? """
                on conflict (changeset_id, db_connection_id) do update set
                    status = excluded.status, exec_type = excluded.exec_type,
                    last_operation_id = excluded.last_operation_id,
                    deployed_at = coalesce(excluded.deployed_at, changeset_state.deployed_at),
                    deployment_id = coalesce(excluded.deployment_id, changeset_state.deployment_id),
                    tag = coalesce(excluded.tag, changeset_state.tag),
                    applied_checksum = coalesce(excluded.applied_checksum, changeset_state.applied_checksum),
                    applied_commit = case when excluded.applied_checksum is null then changeset_state.applied_commit
                                          else coalesce(excluded.applied_commit, case when changeset_state.applied_checksum = excluded.applied_checksum
                                                                                      then changeset_state.applied_commit end) end
                """ : "on conflict (changeset_id, db_connection_id) do nothing";

        jdbc.sql("""
                insert into changeset_state (changeset_id, db_connection_id, status, exec_type,
                    last_operation_id, deployed_at, deployment_id, tag, applied_checksum, applied_commit)
                values (:cs, :conn, :status, :execType, :op, :deployed, :deployment, :tag, :appliedChecksum, :appliedCommit)
                """ + conflict)
                .param("cs", changesetId).param("conn", connectionId).param("status", status)
                .param("execType", "EXECUTED".equals(cs.outcome()) || "PREVIOUSLY_RUN".equals(cs.outcome())
                        ? "EXECUTED" : null)
                .param("op", in.operationId()).param("deployed", ts(deployedAt))
                .param("deployment", in.deploymentId()).param("tag", in.tag())
                .param("appliedChecksum", "EXECUTED".equals(cs.outcome()) ? cs.checksum() : null)
                .param("appliedCommit", "EXECUTED".equals(cs.outcome()) && in.git() != null ? in.git().commit() : null)
                .update();
    }

    // -------------------------------------------------------------- findings

    private void replaceFindings(UUID workspaceId, IngestOperation in, Map<String, UUID> changesetIds) {
        jdbc.sql("delete from policy_finding where operation_id = :id").param("id", in.operationId()).update();
        if (in.policy() == null) {
            return;
        }
        for (IngestOperation.Finding f : orEmpty(in.policy().findings())) {
            Optional<UUID> checkId = jdbc.sql("""
                    select id from policy_check
                     where code = :code and (workspace_id is null or workspace_id = :ws)
                     order by workspace_id nulls last limit 1
                    """)
                    .param("code", f.check()).param("ws", workspaceId).query(UUID.class).optional();
            if (checkId.isEmpty()) {
                // A collector may run a check this server does not know yet. Dropping the
                // finding is the lesser evil: failing the whole ingestion would lose the
                // operation, and ADR-004 says collectors must not be punished for it.
                LOG.warn("finding for unknown check {} ignored (operation {})", f.check(), in.operationId());
                continue;
            }
            int severity = f.severity() == null ? 0 : f.severity();
            jdbc.sql("""
                    insert into policy_finding (id, operation_id, operation_started_at, check_id, changeset_id,
                        severity, result, message, created_at)
                    values (:id, :op, :at, :check, :cs, :sev, :result, :msg, now())
                    """)
                    .param("id", UuidV7.next()).param("op", in.operationId()).param("at", ts(in.startedAt()))
                    .param("check", checkId.get()).param("cs", changesetIds.get(f.changeset()))
                    .param("sev", severity).param("result", f.result() == null ? "WARNED" : f.result())
                    .param("msg", f.message() == null ? f.check() : f.message())
                    .update();
        }
    }

    // --------------------------------------------------------------- helpers

    private static int[] counts(IngestOperation in) {
        IngestOperation.Summary s = in.summary();
        if (s != null) {
            return new int[]{nz(s.executed()), nz(s.previouslyRun()), nz(s.filtered()), nz(s.failed())};
        }
        int[] c = new int[4];
        for (IngestOperation.Changeset cs : orEmpty(in.changesets())) {
            switch (cs.outcome()) {
                case "EXECUTED" -> c[0]++;
                case "PREVIOUSLY_RUN" -> c[1]++;
                case "FILTERED" -> c[2]++;
                case "FAILED" -> c[3]++;
                default -> {
                }
            }
        }
        return c;
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static Long durationOf(IngestOperation in) {
        return in.endedAt() == null ? null : in.endedAt().toEpochMilli() - in.startedAt().toEpochMilli();
    }

    private static boolean isBreakFix(String labels) {
        return Labels.isBreakFix(labels);
    }

    private static String releaseOf(IngestOperation in) {
        return Labels.release(in.labels());
    }

    private static String truncate(String text) {
        return text.length() <= MAX_BLOB_CHARS ? text : text.substring(0, MAX_BLOB_CHARS);
    }

    /** pgjdbc does not bind java.time.Instant, so timestamps travel as OffsetDateTime in UTC. */
    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String slugify(String text) {
        String slug = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return slug.length() > 80 ? slug.substring(0, 80) : slug;
    }

    private static String nameOf(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }

    private static String formatOf(IngestOperation.Changelog changelog) {
        if (changelog.format() != null) {
            return changelog.format().toUpperCase(Locale.ROOT);
        }
        String path = changelog.path().toLowerCase(Locale.ROOT);
        if (path.endsWith(".yml") || path.endsWith(".yaml")) {
            return "YAML";
        }
        if (path.endsWith(".json")) {
            return "JSON";
        }
        return path.endsWith(".sql") ? "SQL" : "XML";
    }

    private static String engineOf(IngestOperation.Connection c) {
        String url = c.jdbcUrl().toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:sqlserver")) {
            return "SQLSERVER";
        }
        if (url.startsWith("jdbc:postgresql")) {
            return "POSTGRESQL";
        }
        if (url.startsWith("jdbc:oracle")) {
            return "ORACLE";
        }
        if (url.startsWith("jdbc:mysql") || url.startsWith("jdbc:mariadb")) {
            return "MYSQL";
        }
        return c.engine() == null ? "UNKNOWN" : c.engine().toUpperCase(Locale.ROOT);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? new ArrayList<>() : list;
    }
}
