package io.github.carloslimajlle.liquimanager.ingest;


import static org.assertj.core.api.Assertions.assertThat;
import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payload;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/**
 * Ingestion against a REAL PostgreSQL, through the real HTTP stack and the real
 * Liquibase schema. See {@link AbstractDatabaseTest} for how the database is wired.
 */
class IngestIntegrationTest extends AbstractDatabaseTest {

    // ------------------------------------------------------------- helpers

    private String token(String... scopes) {
        return serviceToken(scopes);
    }

    private HttpResponse<String> ingest(String token, String body) {
        return call("POST", "/api/v1/ingest/operations", token, body);
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("a first ingestion answers 202 and persists operation, changeset, state and an unverified connection")
    void firstIngestion() throws Exception {
        UUID operationId = UUID.randomUUID();
        String project = "proj-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "conn-" + UUID.randomUUID().toString().substring(0, 8);

        HttpResponse<String> response = ingest(token("ingest"),
                payload(operationId, project, key, uniqueUrl(), "SUCCESS", changeset("SUP-1-001", "EXECUTED")));

        assertThat(response.statusCode()).isEqualTo(202);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("operationId").asString()).isEqualTo(operationId.toString());

        assertThat(jdbc.sql("select status from operation where id = :id").param("id", operationId)
                .query(String.class).single()).isEqualTo("SUCCESS");
        assertThat(jdbc.sql("select type from operation where id = :id").param("id", operationId)
                .query(String.class).single()).isEqualTo("database-change");
        assertThat(jdbc.sql("select verified from db_connection where slug = :k").param("k", key)
                .query(Boolean.class).single()).as("a connection born from ingestion is unverified").isFalse();
        assertThat(jdbc.sql("select verified from changelog c join project p on p.id = c.project_id where p.slug = :p")
                .param("p", project).query(Boolean.class).single()).isFalse();
        assertThat(jdbc.sql("""
                select s.status from changeset_state s
                  join changeset c on c.id = s.changeset_id
                  join changelog l on l.id = c.changelog_id
                  join project p on p.id = l.project_id
                 where p.slug = :p and c.cs_id = 'SUP-1-001'
                """).param("p", project).query(String.class).single()).isEqualTo("EXECUTED");
    }

    @Test
    @DisplayName("resending the same operation answers 200 and duplicates nothing")
    void idempotent() throws Exception {
        UUID operationId = UUID.randomUUID();
        String project = "proj-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        String url = uniqueUrl();
        String token = token("ingest");
        String body = payload(operationId, project, key, url, "SUCCESS", changeset("SUP-2-001", "EXECUTED"));

        assertThat(ingest(token, body).statusCode()).isEqualTo(202);
        assertThat(ingest(token, body).statusCode()).isEqualTo(200);
        assertThat(ingest(token, body).statusCode()).isEqualTo(200);

        assertThat(jdbc.sql("select count(*) from operation where id = :id").param("id", operationId)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from operation_changeset where operation_id = :id")
                .param("id", operationId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from project where slug = :p").param("p", project)
                .query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("a RUNNING operation is updated in place when the final status arrives")
    void runningThenFinal() throws Exception {
        UUID operationId = UUID.randomUUID();
        String project = "proj-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        String url = uniqueUrl();
        String token = token("ingest");

        assertThat(ingest(token, payload(operationId, project, key, url, "RUNNING", "")).statusCode()).isEqualTo(202);
        assertThat(ingest(token, payload(operationId, project, key, url, "FAILURE",
                changeset("SUP-3-001", "FAILED"))).statusCode()).isEqualTo(200);

        assertThat(jdbc.sql("select status from operation where id = :id").param("id", operationId)
                .query(String.class).single()).isEqualTo("FAILURE");
        assertThat(jdbc.sql("select count_failed from operation where id = :id").param("id", operationId)
                .query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("without a connection key, the JDBC fingerprint finds the registered connection")
    void matchesByFingerprint() throws Exception {
        String project = "proj-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        String url = uniqueUrl();
        String token = token("ingest");

        assertThat(ingest(token, payload(UUID.randomUUID(), project, key, url, "SUCCESS", "")).statusCode())
                .isEqualTo(202);
        // Same database, different spelling of the URL, and no key this time.
        String sameDatabase = url.replace("host-", "HOST-") + ";encrypt=true";
        assertThat(ingest(token, payload(UUID.randomUUID(), project, null, sameDatabase, "SUCCESS", "")).statusCode())
                .isEqualTo(202);

        assertThat(jdbc.sql("select count(*) from db_connection where slug = :k").param("k", key)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("select count(distinct db_connection_id) from operation o join project p "
                + "on p.id = o.project_id where p.slug = :p").param("p", project)
                .query(Long.class).single()).as("both operations hit the same connection").isEqualTo(1);
    }

    @Test
    @DisplayName("a FILTERED outcome never downgrades a state we already know")
    void filteredDoesNotDowngrade() throws Exception {
        String project = "proj-" + UUID.randomUUID().toString().substring(0, 8);
        String key = "conn-" + UUID.randomUUID().toString().substring(0, 8);
        String url = uniqueUrl();
        String token = token("ingest");

        ingest(token, payload(UUID.randomUUID(), project, key, url, "SUCCESS", changeset("SUP-4-001", "EXECUTED")));
        ingest(token, payload(UUID.randomUUID(), project, key, url, "SUCCESS", changeset("SUP-4-001", "FILTERED")));

        assertThat(jdbc.sql("""
                select s.status from changeset_state s join changeset c on c.id = s.changeset_id
                  join changelog l on l.id = c.changelog_id join project p on p.id = l.project_id
                 where p.slug = :p and c.cs_id = 'SUP-4-001'
                """).param("p", project).query(String.class).single()).isEqualTo("EXECUTED");
    }

    @Test
    @DisplayName("log and SQL go to blob storage, only the reference stays on the row")
    void blobsAreStoredApart() throws Exception {
        UUID operationId = UUID.randomUUID();
        ingest(token("ingest"), payload(operationId, "proj-" + UUID.randomUUID().toString().substring(0, 8),
                "conn-" + UUID.randomUUID().toString().substring(0, 8), uniqueUrl(), "SUCCESS", ""));

        String logRef = jdbc.sql("select log_ref from operation where id = :id").param("id", operationId)
                .query(String.class).single();
        assertThat(logRef).startsWith("fs:operations/" + operationId);
        assertThat(jdbc.sql("select raw::text from operation where id = :id").param("id", operationId)
                .query(String.class).single()).as("raw payload must not carry the blobs")
                .doesNotContain("second line");
    }

    // ----------------------------------------------------------- rejections

    @Test
    @DisplayName("no token: 401")
    void noToken() throws Exception {
        assertThat(ingest(null, "{}").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a garbage token: 401")
    void badToken() throws Exception {
        assertThat(ingest("lqm_abcdefgh_" + "x".repeat(32), "{}").statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("a valid token without the ingest scope: 403")
    void wrongScope() throws Exception {
        assertThat(ingest(token("checks:read"), "{}").statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("an invalid payload: 422 with the LQM-422 code")
    void invalidPayload() throws Exception {
        HttpResponse<String> response = ingest(token("ingest"), "{\"command\":\"update\"}");

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(JSON.readTree(response.body()).get("code").asString()).isEqualTo("LQM-422");
    }

    @Test
    @DisplayName("a JDBC URL carrying credentials is refused, never stored (RNF-06)")
    void credentialsInUrlAreRefused() throws Exception {
        String project = "proj-" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> response = ingest(token("ingest"), payload(UUID.randomUUID(), project, "k-" + project,
                "jdbc:sqlserver://h:1433;databaseName=x;user=sa;password=Secret123", "SUCCESS", ""));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(jdbc.sql("select count(*) from db_connection where jdbc_url like '%Secret123%'")
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("an unknown environment when registering a connection: 422")
    void unknownEnvironment() throws Exception {
        String body = payload(UUID.randomUUID(), "p-" + UUID.randomUUID().toString().substring(0, 8),
                "k-" + UUID.randomUUID().toString().substring(0, 8), uniqueUrl(), "SUCCESS", "")
                .replace("\"environment\": \"PRD\"", "\"environment\": \"NOPE\"");

        assertThat(ingest(token("ingest"), body).statusCode()).isEqualTo(422);
    }
}
