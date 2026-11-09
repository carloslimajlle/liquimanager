package io.github.carloslimajlle.liquimanager.collector;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.carloslimajlle.liquimanager.client.IngestPayload;
import liquibase.database.Database;

/**
 * Assembles the report of one operation. Pure: it reads what the recorder and the gate
 * produced and puts it in the API shape; it never talks to the network or the database
 * beyond the metadata calls that Liquibase already made.
 */
public final class ReportBuilder {

    private final RunInfo info;
    private final Instant startedAt = Instant.now();
    private final UUID operationId;

    public ReportBuilder(RunInfo info) {
        this(info, UUID.randomUUID());
    }

    /** With an id chosen by the caller, for a runner that must tell LiquiManager which operation answers a request. */
    public ReportBuilder(RunInfo info, UUID operationId) {
        this.info = info;
        this.operationId = operationId;
    }

    public UUID operationId() {
        return operationId;
    }

    /**
     * @param status  SUCCESS, FAILURE or BLOCKED
     * @param error   why it did not succeed, when it did not
     */
    public IngestPayload build(String status, Database database, String jdbcUrl, ChangeSetRecorder recorder,
                               Optional<PolicyGate.Verdict> verdict, String error, Integer exitCode,
                               String log, String sql) {
        return build(status, database, jdbcUrl, recorder, verdict, error, exitCode, log, sql, null);
    }

    /** The same, for an operation that answers another one (a rollback runs on behalf of an earlier operation). */
    public IngestPayload build(String status, Database database, String jdbcUrl, ChangeSetRecorder recorder,
                               Optional<PolicyGate.Verdict> verdict, String error, Integer exitCode,
                               String log, String sql, UUID parentOperationId) {
        return new IngestPayload(
                operationId, info.command(), status, info.source(), executedBy(), startedAt, Instant.now(),
                info.project(), info.environment(), info.deploymentId(), info.contexts(), info.labels(), info.tag(),
                new IngestPayload.Connection(info.connectionKey(), JdbcUrls.sanitize(jdbcUrl),
                        engine(database), version(database)),
                info.changelogPath() == null ? null
                        : new IngestPayload.Changelog(info.changelogPath(), formatOf(info.changelogPath()),
                                recorder != null && recorder.classified()),
                info.git(), info.ci(),
                new IngestPayload.Runtime(liquibaseVersion(), info.collector(), info.collectorVersion(),
                        host(), System.getProperty("java.version")),
                recorder == null ? null : recorder.summary(),
                recorder == null ? List.of() : recorder.changesets(),
                verdict.map(PolicyGate.Verdict::payload).orElse(null),
                error == null ? null : new IngestPayload.Error(error, exitCode),
                log, sql, parentOperationId);
    }

    static String formatOf(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".xml")) {
            return "XML";
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return "YAML";
        }
        if (lower.endsWith(".json")) {
            return "JSON";
        }
        if (lower.endsWith(".sql")) {
            return "SQL";
        }
        return null;
    }

    private static String engine(Database database) {
        try {
            return database == null ? null : database.getShortName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String version(Database database) {
        try {
            return database == null ? null : database.getDatabaseProductVersion();
        } catch (Exception e) {
            return null;
        }
    }

    private static String liquibaseVersion() {
        try {
            return liquibase.util.LiquibaseUtil.getBuildVersion();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static String host() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return null;
        }
    }

    private static String executedBy() {
        String user = System.getenv("GITLAB_USER_LOGIN");
        if (user == null || user.isBlank()) {
            user = System.getProperty("user.name");
        }
        return user == null || user.isBlank() ? "unknown" : user;
    }
}
