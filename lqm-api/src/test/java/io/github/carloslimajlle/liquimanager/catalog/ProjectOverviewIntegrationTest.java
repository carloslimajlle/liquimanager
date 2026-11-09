package io.github.carloslimajlle.liquimanager.catalog;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** The projects list in one call, and the projects a connection is linked to. */
class ProjectOverviewIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    @Test
    @DisplayName("the overview carries changelogs, connections, collector and last operation per project, and the KPIs of the screen")
    void overview() {
        String token = serviceToken("ingest");
        String project = unique("ov");
        String connection = unique("c");
        HttpResponse<String> ingested = call("POST", API + "/ingest/operations", token,
                payloadAt("DEV", UUID.randomUUID(), project, connection, uniqueUrl(), "update", "SUCCESS", Instant.now().minusSeconds(30),
                        changeset("SUP-1", "EXECUTED")));
        assertThat(ingested.statusCode()).as(ingested.body()).isIn(200, 202);

        JsonNode body = json(get(API + "/projects/overview"));

        JsonNode row = null;
        for (JsonNode candidate : body.get("projects")) {
            if (project.equals(candidate.get("slug").asString())) {
                row = candidate;
            }
        }
        assertThat(row).as("the project that just reported").isNotNull();
        assertThat(row.get("changelogs").asInt()).isEqualTo(1);
        assertThat(row.get("connections").asInt()).isEqualTo(1);
        assertThat(row.get("lastOperationStatus").asString()).isEqualTo("SUCCESS");
        assertThat(row.get("lastOperationAt").asString()).isNotBlank();
        JsonNode kpis = body.get("kpis");
        assertThat(kpis.get("projects").asInt()).isEqualTo(body.get("projects").size());
        assertThat(kpis.get("changelogs").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(kpis.has("withCollector")).isTrue();
        assertThat(kpis.has("underPolicy")).isTrue();
    }

    @Test
    @DisplayName("a connection answers with the projects it is linked to, and the form can change them")
    void connectionProjects() {
        String token = serviceToken("ingest");
        String project = unique("lk");
        String connection = unique("c");
        call("POST", API + "/ingest/operations", token, payloadAt("DEV", UUID.randomUUID(), project, connection, uniqueUrl(), "update",
                "SUCCESS", Instant.now().minusSeconds(30), changeset("SUP-2", "EXECUTED")));
        UUID connectionId = jdbc.sql("select id from db_connection where slug = :s").param("s", connection).query(UUID.class).single();
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        jdbc.sql("delete from project_connection where db_connection_id = :c").param("c", connectionId).update();

        assertThat(json(get(API + "/connections/" + connectionId)).get("projectIds")).isEmpty();

        JsonNode current = json(get(API + "/connections/" + connectionId));
        String update = "{\"environmentId\":\"%s\",\"slug\":\"%s\",\"displayName\":\"%s\",\"engine\":\"%s\",\"jdbcUrl\":\"%s\",\"projectIds\":[\"%s\"]}"
                .formatted(current.get("environmentId").asString(), connection, connection, current.get("engine").asString(),
                        current.get("jdbcUrl").asString(), projectId);
        HttpResponse<String> put = callIn("default", "PUT", API + "/connections/" + connectionId, update);
        assertThat(put.statusCode()).as(put.body()).isEqualTo(200);

        JsonNode linked = json(get(API + "/connections/" + connectionId)).get("projectIds");
        assertThat(linked).hasSize(1);
        assertThat(linked.get(0).asString()).isEqualTo(projectId.toString());
    }
}
