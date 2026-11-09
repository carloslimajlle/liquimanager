package io.github.carloslimajlle.liquimanager;

import java.time.Instant;
import java.util.UUID;

/**
 * Builders for ingestion payloads used across the integration tests. Shaped like the
 * example in docs/06; callers vary the ids, environment and changesets.
 *
 * <p>Every operation starts at the same fixed instant, 2026-09-15T19:07:03.120Z, and
 * lasts 998 ms. Tests that look at time windows rely on that.
 */
public final class TestPayloads {

    public static final String STARTED_AT = "2026-09-15T19:07:03.120Z";

    private TestPayloads() {
    }

    /** An operation against PRD. */
    public static String payload(UUID operationId, String project, String connectionKey, String jdbcUrl,
                                 String status, String changesets) {
        return payloadIn("PRD", operationId, project, connectionKey, jdbcUrl, status, changesets);
    }

    public static String payloadIn(String environment, UUID operationId, String project, String connectionKey,
                                   String jdbcUrl, String status, String changesets) {
        return payloadAt(environment, operationId, project, connectionKey, jdbcUrl, "update", status,
                Instant.parse(STARTED_AT), changesets);
    }

    /** The general form: any command, at any instant. Every operation lasts 998 ms. */
    public static String payloadAt(String environment, UUID operationId, String project, String connectionKey,
                                   String jdbcUrl, String command, String status, Instant startedAt,
                                   String changesets) {
        return """
                {
                  "operationId": "%s", "command": "%s", "status": "%s", "source": "pipeline",
                  "executedBy": "p.ricardo",
                  "startedAt": "%s", "endedAt": "%s",
                  "project": "%s", "environment": "%s", "deploymentId": "8123456789",
                  "contexts": "prd", "labels": "release-2.1.0",
                  "connection": { "key": %s, "jdbcUrl": "%s", "engine": "SQLSERVER", "dbVersion": "16.0.4135" },
                  "changelog": { "path": "db/changelog/db.changelog-master.xml", "format": "XML" },
                  "git": { "commit": "9f3c2ab", "branch": "main" },
                  "runtime": { "liquibaseVersion": "5.0.4", "collector": "lqm-cli", "collectorVersion": "1.0.0" },
                  "changesets": [%s],
                  "error": { "message": "boom", "exitCode": 1 },
                  "log": "first line\\nsecond line", "sql": "create index ix on t(a);"
                }
                """.formatted(operationId, command, status, startedAt, startedAt.plusMillis(998), project,
                environment, connectionKey == null ? "null" : "\"" + connectionKey + "\"", jdbcUrl, changesets);
    }

    public static String changeset(String id, String outcome) {
        return changeset(id, outcome, 1);
    }

    public static String changeset(String id, String outcome, int order) {
        return """
                { "id": "%s", "author": "p.ricardo", "filePath": "db/changelog/work/%s.xml",
                  "checksum": "9:abc", "labels": "release-2.1.0", "hasRollback": true,
                  "outcome": "%s", "order": %d, "durationMs": 830 }
                """.formatted(id, id, outcome, order);
    }

    public static String uniqueUrl() {
        return "jdbc:sqlserver://host-" + UUID.randomUUID().toString().substring(0, 8)
                + ".local:1433;databaseName=Core";
    }
}
