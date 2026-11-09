package io.github.carloslimajlle.liquimanager.client;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a collector reports at the end of every Liquibase operation.
 *
 * <p>The Java counterpart of {@code IngestOperation} in contracts/openapi.yaml, which is the
 * source of truth. Null fields are not serialized: the API treats a missing field as unknown,
 * and a collector must never invent a value to fill one.
 */
public record IngestPayload(
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

    /**
     * @param complete true when {@code changesets} lists EVERY changeset of the changelog (the collector classified it before the
     *                 command ran), which is what lets the server notice the ones that are gone
     */
    public record Changelog(String path, String format, Boolean complete) {
    }

    public record Git(String commit, String branch) {
    }

    /** {@code mergeRequest*}: the merge request this pipeline runs for, when it runs for one (what names the branch a changeset came from). */
    public record Ci(String provider, String pipelineId, String jobUrl, String mergeRequestIid, String mergeRequestTitle,
                     String mergeRequestUrl) {

        public Ci(String provider, String pipelineId, String jobUrl) {
            this(provider, pipelineId, jobUrl, null, null, null);
        }

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
}
