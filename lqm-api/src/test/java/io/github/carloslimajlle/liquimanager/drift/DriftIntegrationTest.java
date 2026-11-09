package io.github.carloslimajlle.liquimanager.drift;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/**
 * Drift against a REAL database: the Postgres the tests already use, in a schema of its own that the test
 * then changes behind LiquiManager's back, exactly the way a person with SSMS would.
 *
 * <p>Needs credentials for the monitored database through the ENV secret provider
 * ({@code DRIFT_TEST_USERNAME} / {@code DRIFT_TEST_PASSWORD}), so it skips where they are not set.
 */
@EnabledIfEnvironmentVariable(named = "DRIFT_TEST_USERNAME", matches = ".+")
class DriftIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    @Autowired
    DriftJobs jobs;

    private String workspace;
    private UUID workspaceId;
    private String schema;
    private String connectionId;
    private String connectionSlug;

    @BeforeEach
    void scenario() {
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Drift\"}".formatted(workspace))).get("id").asString());
        String env = json(in("POST", "/environments", "{\"code\":\"HML\",\"name\":\"HML\",\"sortOrder\":1,\"isProduction\":false}"))
                .get("id").asString();
        schema = "drift_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.sql("create schema " + schema).update();
        jdbc.sql("create table " + schema + ".cliente (id int primary key, nome varchar(50) not null)").update();

        connectionSlug = unique("tgt");
        HttpResponse<String> response = in("POST", "/connections", """
                { "environmentId": "%s", "slug": "%s", "displayName": "alvo", "engine": "postgresql", "jdbcUrl": "%s",
                  "defaultSchema": "%s", "secretProvider": "ENV", "secretRef": "drift_test", "driftEnabled": true }
                """.formatted(env, connectionSlug, System.getenv("LQM_TEST_DB_URL"), schema));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        connectionId = json(response).get("id").asString();
    }

    private HttpResponse<String> in(String method, String path, String body) {
        return callIn(workspace, method, API + path, body);
    }

    private JsonNode run() {
        HttpResponse<String> response = in("POST", "/connections/" + connectionId + "/drift-runs", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private void sql(String statement) {
        jdbc.sql(statement.replace("S.", schema + ".")).update();
    }

    private static List<String> names(JsonNode run, String change) {
        return run.get("items").valueStream().filter(i -> change.equals(i.get("change").asString()))
                .map(i -> i.get("objectType").asString() + " " + i.get("objectName").asString()).toList();
    }

    // ------------------------------------------------------ several schemas, grants

    @Test
    @DisplayName("a connection that lists several schemas compares each with itself, and names carry the schema")
    void severalSchemas() {
        String second = schema + "_b";
        jdbc.sql("create schema " + second).update();
        try {
            jdbc.sql("create table " + second + ".cliente (id int primary key, nome varchar(50) not null)").update();
            jdbc.sql("update db_connection set default_schema = :s where id = cast(:id as uuid)")
                    .param("s", schema + ", " + second).param("id", connectionId).update();
            run();

            jdbc.sql("alter table " + second + ".cliente add column email varchar(100)").update();
            JsonNode drift = run();

            assertThat(drift.get("hasDrift").asBoolean()).isTrue();
            assertThat(names(drift, "ADDED")).as("the same table name in both schemas: only the second one changed")
                    .containsExactly("COLUMN " + second + ".cliente.email");
        } finally {
            jdbc.sql("drop schema " + second + " cascade").update();
        }
    }

    @Test
    @DisplayName("a grant given behind LiquiManager's back is drift; a grant that comes with a new table is not extra news")
    void grants() {
        String role = "drift_role_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        jdbc.sql("create role " + role).update();
        try {
            run();
            sql("grant select on S.cliente to " + role);

            JsonNode drift = run();

            assertThat(drift.get("hasDrift").asBoolean()).isTrue();
            assertThat(names(drift, "ADDED")).containsExactly("GRANT " + role + " on " + schema + ".cliente");
            JsonNode grant = drift.get("items").valueStream().filter(i -> "GRANT".equals(i.get("objectType").asString())).findFirst().orElseThrow();
            assertThat(grant.get("details").get("privilege").asString()).isEqualTo("SELECT");

            sql("create table S.novo (id int primary key)");
            sql("grant insert on S.novo to " + role);
            JsonNode withTable = run();
            assertThat(names(withTable, "ADDED")).as("the table, and the grant already told; not the grant on the new table")
                    .containsExactlyInAnyOrder("TABLE novo", "GRANT " + role + " on " + schema + ".cliente");
        } finally {
            jdbc.sql("drop owned by " + role).update();
            jdbc.sql("drop role " + role).update();
        }
    }

    // ------------------------------------------------------------------ runs

    @Test
    @DisplayName("the first run takes the reference and has nothing to compare it with; the next is clean")
    void firstRunIsTheBaseline() {
        JsonNode first = run();
        assertThat(first.get("status").asString()).isEqualTo("SUCCESS");
        assertThat(first.get("hasDrift").asBoolean()).isFalse();
        assertThat(first.get("reference").asString()).contains("first snapshot");

        JsonNode second = run();
        assertThat(second.get("hasDrift").asBoolean()).isFalse();
        assertThat(second.get("reference").asString()).contains("last clean snapshot");
        assertThat(second.get("items")).isEmpty();
    }

    @Test
    @DisplayName("a column added by hand shows up as +1 in COLUMN, and the run is an operation of type drift")
    void columnAddedByHand() {
        run();
        sql("alter table S.cliente add column email varchar(100)");

        JsonNode drift = run();

        assertThat(drift.get("hasDrift").asBoolean()).isTrue();
        assertThat(drift.get("added").asInt()).isEqualTo(1);
        assertThat(names(drift, "ADDED")).containsExactly("COLUMN cliente.email");
        long operations = jdbc.sql("select count(*) from operation where id = :id and type = 'drift' and command = 'diff'")
                .param("id", UUID.fromString(drift.get("operationId").asString())).query(Long.class).single();
        assertThat(operations).isEqualTo(1);
    }

    @Test
    @DisplayName("added, removed and modified objects are told apart, and a new table does not drag its columns along")
    void addedRemovedModified() {
        sql("alter table S.cliente add column legado varchar(10)");
        sql("create table S.pedido (id int primary key, total numeric(10,2))");
        run();
        sql("alter table S.cliente drop column legado");
        sql("alter table S.cliente alter column nome type varchar(120)");
        sql("create table S.novo (id int primary key, x int)");
        sql("create index ix_pedido_total on S.pedido (total)");

        JsonNode drift = run();

        assertThat(names(drift, "REMOVED")).containsExactly("COLUMN cliente.legado");
        assertThat(names(drift, "ADDED")).contains("TABLE novo", "INDEX ix_pedido_total")
                .as("the new table's own columns and key are not extra news").noneMatch(n -> n.startsWith("COLUMN novo"));
        assertThat(names(drift, "MODIFIED")).contains("COLUMN cliente.nome");
        JsonNode modified = drift.get("items").valueStream()
                .filter(i -> "MODIFIED".equals(i.get("change").asString())).findFirst().orElseThrow();
        assertThat(modified.get("details").toString()).contains("before").contains("after");
    }

    @Test
    @DisplayName("an accepted item does not come back while the object is unchanged, and does when it changes again")
    void acceptedItemsAreCarried() {
        run();
        sql("alter table S.cliente add column email varchar(100)");
        JsonNode first = run();
        String itemId = first.get("items").get(0).get("id").asString();

        HttpResponse<String> accept = in("POST", "/drift/items/" + itemId + "/accept", "{\"reason\":\"coluna criada pelo suporte\"}");
        assertThat(accept.statusCode()).as(accept.body()).isEqualTo(200);

        JsonNode next = run();
        assertThat(next.get("hasDrift").asBoolean()).as("carried, not counted").isFalse();
        assertThat(next.get("added").asInt()).isZero();
        assertThat(next.get("items").get(0).get("accepted").asBoolean()).isTrue();
        assertThat(next.get("items").get(0).get("acceptedBy").asString()).isEqualTo("dev");

        sql("alter table S.cliente alter column email type varchar(300)");
        JsonNode again = run();
        assertThat(again.get("hasDrift").asBoolean()).as("the object changed again").isTrue();
    }

    @Test
    @DisplayName("accepting needs a real justification, and accepting lowers the run's own counters")
    void acceptValidation() {
        run();
        sql("alter table S.cliente add column email varchar(100)");
        JsonNode drift = run();
        String run = drift.get("id").asString();
        String itemId = drift.get("items").get(0).get("id").asString();

        assertThat(in("POST", "/drift/items/" + itemId + "/accept", "{\"reason\":\"curto\"}").statusCode()).isEqualTo(422);
        assertThat(in("POST", "/drift/items/" + UUID.randomUUID() + "/accept", "{\"reason\":\"uma justificativa longa\"}").statusCode())
                .isEqualTo(404);
        in("POST", "/drift/items/" + itemId + "/accept", "{\"reason\":\"uma justificativa longa\"}");

        JsonNode after = json(in("GET", "/drift/runs/" + run, null));
        assertThat(after.get("added").asInt()).isZero();
        assertThat(after.get("hasDrift").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("re-baselining makes the current state the clean one")
    void rebaseline() {
        run();
        sql("alter table S.cliente add column email varchar(100)");
        assertThat(run().get("hasDrift").asBoolean()).isTrue();

        assertThat(in("POST", "/connections/" + connectionId + "/drift-reference", null).statusCode()).isEqualTo(200);

        assertThat(run().get("hasDrift").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the changelog of a run adds what was added by hand")
    void changelogOfTheDrift() {
        run();
        sql("alter table S.cliente add column email varchar(100)");
        String runId = run().get("id").asString();

        HttpResponse<String> response = in("POST", "/drift/runs/" + runId + "/changelog", null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).contains("xml");
        assertThat(response.body()).as("body: %s", response.body()).contains("databaseChangeLog").contains("addColumn").contains("email");
    }

    @Test
    @DisplayName("the generated changelog applies to a database in the clean state and brings it to the drifted one")
    void generatedChangelogApplies() throws Exception {
        run();
        sql("alter table S.cliente add column email varchar(100)");
        String runId = run().get("id").asString();
        String xml = in("POST", "/drift/runs/" + runId + "/changelog", null).body();

        String other = "drift_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbc.sql("create schema " + other).update();
        jdbc.sql("create table " + other + ".cliente (id int primary key, nome varchar(50) not null)").update();
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("lqm-generated");
        java.nio.file.Files.writeString(dir.resolve("drift.xml"), xml);
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(System.getenv("LQM_TEST_DB_URL"),
                System.getenv("DRIFT_TEST_USERNAME"), System.getenv("DRIFT_TEST_PASSWORD"))) {
            liquibase.database.Database database = liquibase.database.DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new liquibase.database.jvm.JdbcConnection(connection));
            database.setDefaultSchemaName(other);
            database.setLiquibaseSchemaName(other);
            new liquibase.Liquibase("drift.xml", new liquibase.resource.DirectoryResourceAccessor(dir), database)
                    .update(new liquibase.Contexts(), new liquibase.LabelExpression());
        }

        long email = jdbc.sql("select count(*) from information_schema.columns where table_schema = :s and table_name = 'cliente' "
                + "and column_name = 'email' and character_maximum_length = 100").param("s", other).query(Long.class).single();
        assertThat(email).isEqualTo(1);
    }

    @Test
    @DisplayName("a database that cannot be reached is a FAILED run with the reason, not an error response")
    void unreachable() {
        String env = json(in("GET", "/environments", null)).get(0).get("id").asString();
        HttpResponse<String> created = in("POST", "/connections", """
                { "environmentId": "%s", "slug": "%s", "displayName": "fora", "engine": "postgresql",
                  "jdbcUrl": "jdbc:postgresql://127.0.0.1:1/nada", "secretProvider": "ENV", "secretRef": "drift_test" }
                """.formatted(env, unique("dead")));
        String id = json(created).get("id").asString();

        HttpResponse<String> response = in("POST", "/connections/" + id + "/drift-runs", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("status").asString()).isEqualTo("FAILED");
        assertThat(body.get("error").asString()).contains("could not connect").doesNotContain("password");
        assertThat(body.get("hasDrift")).as("unknown, not clean").isNull();
    }

    // --------------------------------------------------------------- scheduling

    @Test
    @DisplayName("the scheduler runs a monitored connection that is due, and leaves one that is not")
    void scheduler() {
        int first = jobs.runDue(Instant.now());
        assertThat(first).as("never ran: due").isGreaterThanOrEqualTo(1);
        assertThat(jobs.runDue(Instant.now())).as("just ran, cron every 6 hours: not due").isZero();

        // Pretend the last run was a day ago.
        jdbc.sql("update drift_run set started_at = started_at - interval '1 day' where db_connection_id = cast(:id as uuid)")
                .param("id", connectionId).update();
        assertThat(jobs.runDue(Instant.now())).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a connection that is not monitored is never run by the scheduler")
    void notMonitoredIsLeftAlone() {
        in("PUT", "/connections/" + connectionId, """
                { "driftEnabled": false }
                """);
        jdbc.sql("update db_connection set drift_enabled = false where id = cast(:id as uuid)").param("id", connectionId).update();

        jobs.runDue(Instant.now());

        long runs = jdbc.sql("select count(*) from drift_run where db_connection_id = cast(:id as uuid)")
                .param("id", connectionId).query(Long.class).single();
        assertThat(runs).isZero();
    }

    @Test
    @DisplayName("after a successful update the reference is refreshed, so what the update did is not drift")
    void referenceFollowsTheUpdate() throws InterruptedException {
        run();
        sql("alter table S.cliente add column coluna_do_update varchar(10)");
        String token = tokens.issue(workspaceId, unique("t"), List.of("ingest"), null,
                Instant.now().plusSeconds(3600), "integration-test").token();
        String payload = payloadAt("HML", UUID.randomUUID(), unique("proj"), connectionSlug, "jdbc:postgresql://irrelevant/x",
                "update", "SUCCESS", Instant.now().minusSeconds(5), changeset("SUP-5", "EXECUTED"));
        assertThat(call("POST", API + "/ingest/operations", token, payload).statusCode()).isIn(200, 202);

        // The refresh is asynchronous: wait for the new reference rather than for a fixed time.
        long deadline = System.currentTimeMillis() + 15_000;
        long references = 0;
        while (System.currentTimeMillis() < deadline) {
            references = jdbc.sql("select count(*) from schema_snapshot where db_connection_id = cast(:id as uuid)")
                    .param("id", connectionId).query(Long.class).single();
            if (references >= 2) {
                break;
            }
            Thread.sleep(250);
        }
        assertThat(references).as("a second reference was stored").isGreaterThanOrEqualTo(2);
        assertThat(run().get("hasDrift").asBoolean()).as("what the update did is the new clean").isFalse();
    }

    // ------------------------------------------------------------------ reading

    @Test
    @DisplayName("the summary, the weekly series and the runs list agree with what happened")
    void summaryAndList() {
        run();
        sql("alter table S.cliente add column email varchar(100)");
        run();
        run();

        JsonNode summary = json(in("GET", "/drift/summary?period=30d", null));
        // The drift is still there on the third run: nothing accepted it, re-baselined or updated over it.
        assertThat(summary.get("driftDetected").asLong()).isEqualTo(2);
        assertThat(summary.get("cleanChecks").asLong()).isEqualTo(1);
        assertThat(summary.get("driftRate").asDouble()).isEqualTo(66.7);
        assertThat(summary.get("items").get("added").asLong()).isEqualTo(2);
        assertThat(summary.get("weekly")).hasSize(8);
        assertThat(summary.get("diverged").get("connection").asString()).isEqualTo(connectionSlug);
        assertThat(summary.get("diverged").get("byType").get(0).get("type").asString()).isEqualTo("COLUMN");
        assertThat(summary.get("coverage").get("monitored").asLong()).isEqualTo(1);

        assertThat(json(in("GET", "/drift/runs?filter=drift", null)).get("totalElements").asLong()).isEqualTo(2);
        assertThat(json(in("GET", "/drift/runs?filter=clean", null)).get("totalElements").asLong()).isEqualTo(1);
        assertThat(json(in("GET", "/drift/runs?connection=" + connectionSlug, null)).get("content").get(0).get("environment").asString())
                .isEqualTo("HML");
        assertThat(in("GET", "/drift/runs?period=forever", null).statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("with no runs there is no drift rate to invent")
    void noRunsNoRate() {
        JsonNode summary = json(in("GET", "/drift/summary", null));

        assertThat(summary.get("driftRate")).isNull();
        assertThat(summary.get("averageItems")).isNull();
        assertThat(summary.get("driftDetected").asLong()).isZero();
    }
}
