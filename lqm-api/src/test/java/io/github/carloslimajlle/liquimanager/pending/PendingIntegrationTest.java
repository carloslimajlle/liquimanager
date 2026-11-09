package io.github.carloslimajlle.liquimanager.pending;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** What waits for a person to decide: only requests of others, only where the person holds the permission. */
class PendingIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String workspace;

    private void bind(String user, String... permissions) {
        String principal = json(callIn(workspace, "POST", API + "/principals",
                "{\"type\":\"USER\",\"externalId\":\"%s\",\"displayName\":\"%s\"}".formatted(user, user))).get("id").asString();
        StringBuilder perms = new StringBuilder();
        for (String permission : permissions) {
            perms.append('"').append(permission).append("\",");
        }
        HttpResponse<String> response = callIn(workspace, "POST", API + "/access/bindings",
                "{\"principalId\":\"%s\",\"permissions\":[%s\"assignment:view\"],\"scopeType\":\"WORKSPACE\"}".formatted(principal, perms));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private JsonNode pending(String user) {
        HttpResponse<String> response = callAs(user, workspace, "GET", API + "/me/pending", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    @Test
    @DisplayName("a request waiting for approval is listed for who may approve it, not for who asked and not for who cannot")
    void listsWhatThePersonCanDecide() {
        workspace = unique("ws");
        UUID workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Pend\"}".formatted(workspace))).get("id").asString());
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}");
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"HML\",\"name\":\"HML\",\"sortOrder\":2,\"isProduction\":false}");
        // Principals are unique across workspaces, so these names cannot be shared with another test.
        String ana = unique("ana");
        String bia = unique("bia");
        String carla = unique("carla");
        String project = unique("proj");
        String token = tokens.issue(workspaceId, unique("ing"), List.of("ingest"), null, Instant.now().plusSeconds(3600), "it").token();
        String dev = unique("dev");
        String hml = unique("hml");
        for (String[] target : new String[][] {{"DEV", dev}, {"HML", hml}}) {
            call("POST", API + "/ingest/operations", token, payloadAt(target[0], UUID.randomUUID(), project, target[1], uniqueUrl(),
                    "update", "SUCCESS", Instant.now().minusSeconds(60), changeset("SUP-1", "EXECUTED")));
        }
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        String pipeline = json(callIn(workspace, "POST", API + "/pipelines",
                "{\"name\":\"%s\",\"projectId\":\"%s\"}".formatted(unique("PL"), projectId))).get("id").asString();
        String hmlStage = null;
        int order = 1;
        for (String[] target : new String[][] {{"DEV", dev}, {"HML", hml}}) {
            String environmentId = null;
            for (JsonNode e : json(callIn(workspace, "GET", API + "/environments", null))) {
                if (target[0].equals(e.get("code").asString())) {
                    environmentId = e.get("id").asString();
                }
            }
            UUID connection = jdbc.sql("select id from db_connection where slug = :s").param("s", target[1]).query(UUID.class).single();
            JsonNode stage = json(callIn(workspace, "POST", API + "/pipelines/" + pipeline + "/stages",
                    "{\"sortOrder\":%d,\"environmentId\":\"%s\",\"dbConnectionId\":\"%s\",\"requiresApproval\":true}"
                            .formatted(order++, environmentId, connection)));
            hmlStage = stage.get("id").asString();
        }
        jdbc.sql("""
                insert into promotion (id, workspace_id, pipeline_id, stage_id, environment, status, requested_by)
                values (:id, :ws, :pl, :st, 'HML', 'REQUESTED', :asker)
                """).param("id", UUID.randomUUID()).param("ws", workspaceId).param("pl", UUID.fromString(pipeline))
                .param("st", UUID.fromString(hmlStage)).param("asker", ana).update();

        bind(ana, "promote:hml");
        bind(bia, "promote:hml");
        bind(carla);

        assertThat(pending(bia).get("total").asInt()).isEqualTo(1);
        JsonNode item = pending(bia).get("items").get(0);
        assertThat(item.get("kind").asString()).isEqualTo("PROMOTION");
        assertThat(item.get("environment").asString()).isEqualTo("HML");
        assertThat(item.get("requestedBy").asString()).isEqualTo(ana);
        assertThat(item.get("pipelineId").asString()).isEqualTo(pipeline);

        assertThat(pending(ana).get("total").asInt()).as("whoever asked does not approve their own request (SoD-2)").isZero();
        assertThat(pending(carla).get("total").asInt()).as("no promote:hml").isZero();
    }
}
