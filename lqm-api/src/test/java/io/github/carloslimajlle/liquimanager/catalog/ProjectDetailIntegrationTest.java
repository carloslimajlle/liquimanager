package io.github.carloslimajlle.liquimanager.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** The project page (summary and linked connections), the environment order and the workspace settings. */
class ProjectDetailIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String workspace;
    private UUID workspaceId;

    @BeforeEach
    void ownWorkspace() {
        workspace = unique("ws");
        JsonNode created = json(post(API + "/workspaces", "{\"slug\":\"%s\",\"name\":\"Detalhe\"}".formatted(workspace)));
        workspaceId = UUID.fromString(created.get("id").asString());
    }

    private JsonNode in(String method, String path, String body) {
        return json(callIn(workspace, method, API + path, body));
    }

    private String environment(String code, int order) {
        return in("POST", "/environments", "{\"code\":\"%s\",\"name\":\"%s\",\"sortOrder\":%d}".formatted(code, code, order)).get("id").asString();
    }

    private String connection(String environmentId, String slug) {
        return in("POST", "/connections", """
                {"environmentId":"%s","slug":"%s","displayName":"%s","engine":"POSTGRESQL","jdbcUrl":"jdbc:postgresql://h/%s"}
                """.formatted(environmentId, slug, slug, slug)).get("id").asString();
    }

    @Test
    @DisplayName("the project's connections are replaced as a set, in pipeline order, and the change is audited")
    void linkConnections() {
        String project = in("POST", "/projects", "{\"slug\":\"%s\",\"name\":\"P\"}".formatted(unique("p"))).get("id").asString();
        String dev = environment("DEV", 1);
        String prd = environment("PRD", 2);
        String a = connection(prd, unique("prd"));
        String b = connection(dev, unique("dev"));

        JsonNode linked = in("PUT", "/projects/" + project + "/connections", "{\"connectionIds\":[\"%s\",\"%s\"]}".formatted(a, b));
        assertThat(linked).hasSize(2);
        assertThat(linked.get(0).get("environmentCode").asString()).as("DEV comes before PRD").isEqualTo("DEV");
        assertThat(in("GET", "/projects/" + project + "/connections", null)).hasSize(2);

        assertThat(in("PUT", "/projects/" + project + "/connections", "{\"connectionIds\":[\"%s\"]}".formatted(a))).hasSize(1);
        assertThat(in("PUT", "/projects/" + project + "/connections", "{\"connectionIds\":[]}")).isEmpty();
        assertThat(jdbc.sql("select count(*) from audit_event where target_id = :id and action = 'project.connections'")
                .param("id", project).query(Long.class).single()).isEqualTo(3L);

        assertThat(callIn(workspace, "PUT", API + "/projects/" + project + "/connections",
                "{\"connectionIds\":[\"%s\"]}".formatted(UUID.randomUUID())).statusCode()).as("not from this workspace").isEqualTo(422);
    }

    @Test
    @DisplayName("the summary counts the last 30 days of operations, what hangs off the project, and starts empty")
    void summary() {
        String project = in("POST", "/projects", "{\"slug\":\"%s\",\"name\":\"P\"}".formatted(unique("p"))).get("id").asString();
        JsonNode empty = in("GET", "/projects/" + project + "/summary", null);
        assertThat(empty.get("finished").asLong()).isZero();
        assertThat(empty.has("successRate")).as("no operation, no rate").isFalse();
        assertThat(empty.get("connections").asLong()).isZero();
        assertThat(empty.get("openDrift").asLong()).isZero();

        String env = environment("DEV", 1);
        String conn = connection(env, unique("c"));
        in("PUT", "/projects/" + project + "/connections", "{\"connectionIds\":[\"%s\"]}".formatted(conn));
        in("POST", "/projects/" + project + "/changelogs", "{\"name\":\"c\",\"path\":\"db/c.xml\",\"format\":\"XML\"}");
        for (String status : List.of("SUCCESS", "SUCCESS", "SUCCESS", "FAILURE")) {
            jdbc.sql("""
                    insert into operation (id, workspace_id, project_id, db_connection_id, command, type, status, source, executed_by, started_at)
                    values (gen_random_uuid(), :ws, :p, :c, 'update', 'database-change', :s, 'pipeline', 'ci', now())
                    """).param("ws", workspaceId).param("p", UUID.fromString(project)).param("c", UUID.fromString(conn)).param("s", status).update();
        }

        JsonNode summary = in("GET", "/projects/" + project + "/summary", null);
        assertThat(summary.get("finished").asLong()).isEqualTo(4);
        assertThat(summary.get("failed").asLong()).isEqualTo(1);
        assertThat(summary.get("successRate").asDouble()).isEqualTo(75.0);
        assertThat(summary.get("connections").asLong()).isEqualTo(1);
        assertThat(summary.get("changelogs").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("the environment order is set in one go, must list every environment once, and is audited")
    void reorderEnvironments() {
        List<String> ids = new ArrayList<>(List.of(environment("DEV", 1), environment("HML", 2), environment("PRD", 3)));

        List<String> reversed = new ArrayList<>(ids.reversed());
        JsonNode after = in("PUT", "/environments/order", "{\"ids\":[%s]}".formatted(String.join(",", reversed.stream().map(i -> "\"" + i + "\"").toList())));
        assertThat(after.get(0).get("code").asString()).isEqualTo("PRD");
        assertThat(after.get(2).get("code").asString()).isEqualTo("DEV");
        assertThat(in("GET", "/environments", null).get(0).get("code").asString()).isEqualTo("PRD");

        assertThat(callIn(workspace, "PUT", API + "/environments/order", "{\"ids\":[\"%s\"]}".formatted(ids.get(0))).statusCode()).as("one is missing").isEqualTo(422);
        assertThat(callIn(workspace, "PUT", API + "/environments/order",
                "{\"ids\":[\"%s\",\"%s\",\"%s\"]}".formatted(ids.get(0), ids.get(0), ids.get(1))).statusCode()).as("one twice").isEqualTo(422);
        assertThat(jdbc.sql("select count(*) from audit_event where workspace_id = :ws and action = 'environment.reorder'")
                .param("ws", workspaceId).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    @DisplayName("the workspace settings are validated against their limits and audited with before and after")
    void settings() {
        JsonNode updated = in("PUT", "/workspaces/" + workspaceId + "/settings",
                "{\"jobRunRetentionDays\":30,\"notificationRetentionDays\":60,\"promotionTtlHours\":0}");
        assertThat(updated.get("jobRunRetentionDays").asInt()).isEqualTo(30);
        assertThat(updated.get("notificationRetentionDays").asInt()).isEqualTo(60);
        assertThat(updated.get("promotionTtlHours").asInt()).isZero();

        for (String bad : List.of("{\"jobRunRetentionDays\":1,\"notificationRetentionDays\":60,\"promotionTtlHours\":1}",
                "{\"jobRunRetentionDays\":30,\"notificationRetentionDays\":60,\"promotionTtlHours\":-1}",
                "{\"jobRunRetentionDays\":30,\"notificationRetentionDays\":60}")) {
            assertThat(callIn(workspace, "PUT", API + "/workspaces/" + workspaceId + "/settings", bad).statusCode()).as(bad).isEqualTo(422);
        }
        assertThat(jdbc.sql("select before ->> 'jobRunRetentionDays' from audit_event where target_id = :id and action = 'workspace.settings'")
                .param("id", workspaceId.toString()).query(String.class).single()).isEqualTo("90");
    }
}
