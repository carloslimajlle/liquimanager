package io.github.carloslimajlle.liquimanager.scanner;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import tools.jackson.databind.JsonNode;

/**
 * The scanner against a database Liquibase REALLY ran on: the changelog is applied with Liquibase itself, so
 * DATABASECHANGELOG, its DEPLOYMENT_ID and the lock table are the genuine article.
 */
@EnabledIfEnvironmentVariable(named = "DRIFT_TEST_USERNAME", matches = ".+")
class ScannerIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    @Autowired
    ScannerJob job;

    private String workspace;
    private UUID workspaceId;
    private String schema;
    private String connectionId;
    private String connectionSlug;
    private Path dir;
    private int applied;

    @BeforeEach
    void scenario() throws Exception {
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Scanner\"}".formatted(workspace))).get("id").asString());
        String env = json(in("POST", "/environments", "{\"code\":\"HML\",\"name\":\"HML\",\"sortOrder\":1,\"isProduction\":false}"))
                .get("id").asString();
        schema = "scan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.sql("create schema " + schema).update();
        dir = Files.createTempDirectory("lqm-scan");

        connectionSlug = unique("tgt");
        HttpResponse<String> response = in("POST", "/connections", """
                { "environmentId": "%s", "slug": "%s", "displayName": "alvo", "engine": "postgresql", "jdbcUrl": "%s",
                  "defaultSchema": "%s", "secretProvider": "ENV", "secretRef": "drift_test" }
                """.formatted(env, connectionSlug, System.getenv("LQM_TEST_DB_URL"), schema));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        connectionId = json(response).get("id").asString();

        // The scheduler scans EVERY due connection of the database, and this database is shared and never cleaned: the
        // ones earlier runs left behind (each one that fails to connect costs a timeout) made this class slower on every
        // run. Only this test's connection may be due.
        jdbc.sql("update db_connection set scanner_enabled = false where id <> cast(:id as uuid) and scanner_enabled")
                .param("id", connectionId).update();
    }

    private HttpResponse<String> in(String method, String path, String body) {
        return callIn(workspace, method, API + path, body);
    }

    /** Runs Liquibase on the monitored schema with {@code total} changesets in the changelog: the new ones are the deployment. */
    private void liquibase(int total) throws Exception {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                """);
        for (int i = 1; i <= total; i++) {
            xml.append("<changeSet id=\"SUP-%d\" author=\"p.ricardo\"><createTable tableName=\"t%d\"><column name=\"id\" type=\"int\"/></createTable></changeSet>\n"
                    .formatted(i, i));
        }
        xml.append("</databaseChangeLog>");
        Files.writeString(dir.resolve("changelog.xml"), xml.toString());
        try (Connection connection = DriverManager.getConnection(System.getenv("LQM_TEST_DB_URL"),
                System.getenv("DRIFT_TEST_USERNAME"), System.getenv("DRIFT_TEST_PASSWORD"))) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            database.setDefaultSchemaName(schema);
            database.setLiquibaseSchemaName(schema);
            new Liquibase("changelog.xml", new DirectoryResourceAccessor(dir), database)
                    .update(new Contexts(), new LabelExpression());
        }
        // Liquibase keeps the same DEPLOYMENT_ID for every update run inside ONE process, and here every run is in the
        // same test JVM. In real life each deployment is its own process, so give the rows this run wrote an id of
        // their own, which is what the scanner sees in production.
        String fresh = String.valueOf(1_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
        for (int i = applied + 1; i <= total; i++) {
            jdbc.sql("update " + schema + ".databasechangelog set deployment_id = :d where id = :id")
                    .param("d", fresh).param("id", "SUP-" + i).update();
        }
        applied = total;
        // DATEEXECUTED has the clock's resolution; keep deployments apart so "newer than last seen" is unambiguous.
        Thread.sleep(1100);
    }

    private JsonNode scan() {
        HttpResponse<String> response = in("POST", "/connections/" + connectionId + "/scan", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private long observedOperations() {
        return jdbc.sql("select count(*) from operation where db_connection_id = cast(:id as uuid) and command = 'observed'")
                .param("id", connectionId).query(Long.class).single();
    }

    private long outOfProcessFindings() {
        return jdbc.sql("""
                select count(*) from policy_finding f join policy_check pc on pc.id = f.check_id
                  join operation o on o.id = f.operation_id and o.started_at = f.operation_started_at
                 where pc.code = 'OutOfProcessChange' and o.db_connection_id = cast(:id as uuid)
                """).param("id", connectionId).query(Long.class).single();
    }

    // ------------------------------------------------------------------- tests

    @Test
    @DisplayName("the first pass imports the history as observed operations without calling any of it out of process")
    void firstPassImportsHistory() throws Exception {
        liquibase(2);

        JsonNode result = scan();

        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("firstPass").asBoolean()).isTrue();
        assertThat(result.get("deployments").asInt()).isEqualTo(1);
        assertThat(result.get("outOfProcess").asInt()).isZero();
        assertThat(observedOperations()).isEqualTo(1);
        assertThat(outOfProcessFindings()).isZero();
        long states = jdbc.sql("select count(*) from changeset_state where db_connection_id = cast(:id as uuid) and status = 'EXECUTED'")
                .param("id", connectionId).query(Long.class).single();
        assertThat(states).as("the matrix learns what ran").isEqualTo(2);
    }

    @Test
    @DisplayName("a deployment nobody reported afterwards is out of process, with a finding at the check's severity")
    void laterDeploymentIsOutOfProcess() throws Exception {
        liquibase(1);
        scan();

        liquibase(3);
        JsonNode result = scan();

        assertThat(result.get("firstPass").asBoolean()).isFalse();
        assertThat(result.get("outOfProcess").asInt()).as("scan result: %s", result).isEqualTo(1);
        assertThat(observedOperations()).isEqualTo(2);
        assertThat(outOfProcessFindings()).as("one per changeset of the deployment").isEqualTo(2);
        JsonNode operation = json(in("GET", "/operations?type=database-change&size=50", null));
        assertThat(operation.toString()).contains("observed").contains("scanner");
        int severity = jdbc.sql("select max(f.severity) from policy_finding f join policy_check pc on pc.id = f.check_id "
                + "where pc.code = 'OutOfProcessChange'").query(Integer.class).single();
        assertThat(severity).isEqualTo(3);
    }

    @Test
    @DisplayName("scanning again finds nothing new, and does not duplicate what it already reported")
    void rescanIsQuiet() throws Exception {
        liquibase(1);
        scan();
        liquibase(2);
        scan();

        JsonNode again = scan();

        assertThat(again.get("deployments").asInt()).isZero();
        assertThat(observedOperations()).isEqualTo(2);
    }

    @Test
    @DisplayName("a deployment that a collector DID report is not out of process")
    void reportedDeploymentIsKnown() throws Exception {
        liquibase(1);
        scan();
        liquibase(2);
        String deploymentId = jdbc.sql("select deployment_id from " + schema + ".databasechangelog where id = 'SUP-2'")
                .query(String.class).single();
        String token = tokens.issue(workspaceId, unique("t"), List.of("ingest"), null,
                Instant.now().plusSeconds(3600), "integration-test").token();
        String payload = payloadAt("HML", UUID.randomUUID(), unique("proj"), connectionSlug, "jdbc:postgresql://x/y", "update",
                "SUCCESS", Instant.now().minusSeconds(30), changeset("SUP-2", "EXECUTED"))
                .replace("\"deploymentId\": \"8123456789\"", "\"deploymentId\": \"" + deploymentId + "\"");
        assertThat(call("POST", API + "/ingest/operations", token, payload).statusCode()).isIn(200, 202);

        JsonNode result = scan();

        assertThat(result.get("outOfProcess").asInt()).isZero();
        assertThat(outOfProcessFindings()).isZero();
    }

    @Test
    @DisplayName("a lock held for over 15 minutes is reported as stuck; a fresh one is not")
    void stuckLock() throws Exception {
        liquibase(1);
        scan();

        jdbc.sql("update " + schema + ".databasechangeloglock set locked = true, lockgranted = now(), lockedby = 'x' where id = 1").update();
        assertThat(scan().get("stuckLockSince")).as("just taken").isNull();

        jdbc.sql("update " + schema + ".databasechangeloglock set lockgranted = now() - interval '20 minutes' where id = 1").update();
        JsonNode stuck = scan();
        assertThat(stuck.get("stuckLockSince")).isNotNull();
        assertThat(json(in("GET", "/connections/" + connectionId + "/scanner", null)).get("stuckLockSince")).isNotNull();

        jdbc.sql("update " + schema + ".databasechangeloglock set locked = false, lockgranted = null, lockedby = null where id = 1").update();
        assertThat(scan().get("stuckLockSince")).isNull();
    }

    @Test
    @DisplayName("a database that cannot be read is a failed pass with the reason, and the next one starts where it left off")
    void unreadable() throws Exception {
        liquibase(1);
        scan();
        jdbc.sql("alter table " + schema + ".databasechangelog rename to databasechangelog_gone").update();
        try {
            JsonNode result = scan();
            assertThat(result.get("ok").asBoolean()).isFalse();
            assertThat(result.get("error").asString()).isNotBlank();
            assertThat(json(in("GET", "/connections/" + connectionId + "/scanner", null)).get("lastOk").asBoolean()).isFalse();
        } finally {
            jdbc.sql("alter table " + schema + ".databasechangelog_gone rename to databasechangelog").update();
        }
        liquibase(2);
        assertThat(scan().get("outOfProcess").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a first pass that fails leaves no watermark, and the next pass still runs")
    void failedFirstPass() throws Exception {
        liquibase(1);
        jdbc.sql("alter table " + schema + ".databasechangelog rename to databasechangelog_gone").update();
        try {
            assertThat(scan().get("ok").asBoolean()).isFalse();
            assertThat(scan().get("ok").asBoolean()).isFalse();
        } finally {
            jdbc.sql("alter table " + schema + ".databasechangelog_gone rename to databasechangelog").update();
        }
        assertThat(scan().get("ok").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the scheduler scans what is due and honours the interval, and leaves unverified connections alone")
    void scheduler() throws Exception {
        liquibase(1);
        assertThat(job.runDue(Instant.now())).as("never scanned").isGreaterThanOrEqualTo(1);
        assertThat(job.runDue(Instant.now())).as("interval not elapsed").isZero();

        jdbc.sql("update scanner_state set last_run_at = now() - interval '1 hour' where db_connection_id = cast(:id as uuid)")
                .param("id", connectionId).update();
        assertThat(job.runDue(Instant.now())).isGreaterThanOrEqualTo(1);

        jdbc.sql("update scanner_state set last_run_at = now() - interval '1 hour' where db_connection_id = cast(:id as uuid)")
                .param("id", connectionId).update();
        jdbc.sql("update db_connection set verified = false where id = cast(:id as uuid)").param("id", connectionId).update();
        assertThat(job.runDue(Instant.now())).isZero();
        assertThat(applied).isEqualTo(1);
    }
}
