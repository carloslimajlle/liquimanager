package io.github.carloslimajlle.liquimanager.ingest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload sent by collectors at the end of every Liquibase operation.
 *
 * <p>Mirrors {@code IngestOperation} in contracts/openapi.yaml; the contract is the
 * source of truth. Unknown properties are ignored on purpose, so a newer collector
 * talking to an older API does not fail just because it sends an extra field.
 */
public record IngestOperation(
        UUID operationId,
        String command,
        String status,
        String source,
        String executedBy,
        Instant startedAt,
        Instant endedAt,
        String project,
        String environment,
        String deploymentId,
        String contexts,
        String labels,
        String tag,
        Connection connection,
        Changelog changelog,
        Git git,
        Ci ci,
        Runtime runtime,
        Summary summary,
        List<Changeset> changesets,
        Policy policy,
        Error error,
        String log,
        String sql,
        UUID parentOperationId) {

    public record Connection(String key, String jdbcUrl, String engine, String dbVersion) {
    }

    /** {@code complete}: the changesets of the report are all of the changelog (see the collector); null from older collectors. */
    public record Changelog(String path, String format, Boolean complete) {
    }

    public record Git(String commit, String branch) {
    }

    public record Ci(String provider, String pipelineId, String jobUrl, String mergeRequestIid, String mergeRequestTitle,
                     String mergeRequestUrl) {
    }

    public record Runtime(String liquibaseVersion, String collector, String collectorVersion,
                          String host, String javaVersion) {
    }

    public record Summary(Integer executed, Integer previouslyRun, Integer filtered, Integer failed) {
    }

    public record Changeset(String id, String author, String filePath, String checksum, String labels,
                            String contexts, Boolean hasRollback, String outcome, String filterReason,
                            Integer order, Instant startedAt, Long durationMs, String error) {
    }

    public record Policy(String gateResult, List<Finding> findings) {
    }

    public record Finding(String check, Integer severity, String result, String changeset, String message) {
    }

    public record Error(String message, Integer exitCode) {
    }

    /** The payload without the large text fields, which live in blob storage. */
    public IngestOperation withoutBlobs() {
        return new IngestOperation(operationId, command, status, source, executedBy, startedAt, endedAt,
                project, environment, deploymentId, contexts, labels, tag, connection, changelog, git, ci,
                runtime, summary, changesets, policy, error, null, null, parentOperationId);
    }
}
