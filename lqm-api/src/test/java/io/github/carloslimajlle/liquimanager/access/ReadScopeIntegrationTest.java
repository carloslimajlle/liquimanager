package io.github.carloslimajlle.liquimanager.access;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** A binding on a project lets its holder read that project and what hangs off it, not the others; a workspace-wide one reads all. */
class ReadScopeIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String workspace;

    private void bind(String user, String scopeType, String scopeId) {
        String principal = json(callIn(workspace, "POST", API + "/principals",
                "{\"type\":\"USER\",\"externalId\":\"%s\",\"displayName\":\"%s\"}".formatted(user, user))).get("id").asString();
        String scope = scopeId == null ? "" : ",\"scopeId\":\"" + scopeId + "\"";
        HttpResponse<String> response = callIn(workspace, "POST", API + "/access/bindings",
                "{\"principalId\":\"%s\",\"permissions\":[\"assignment:view\"],\"scopeType\":\"%s\"%s}".formatted(principal, scopeType, scope));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private List<String> names(HttpResponse<String> response, String field) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        JsonNode rows = body.has("content") ? body.get("content") : body;
        List<String> out = new ArrayList<>();
        rows.forEach(row -> out.add(row.get(field).asString()));
        return out;
    }

    @Test
    @DisplayName("lists show only the rows of the scopes the caller holds, and a resource outside them answers 403")
    void readsFollowTheScope() {
        workspace = unique("ws");
        UUID workspaceId = UUID.fromString(json(post(API + "/workspaces", "{\"slug\":\"%s\",\"name\":\"Read\"}".formatted(workspace))).get("id").asString());
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}");
        String token = tokens.issue(workspaceId, unique("ing"), List.of("ingest"), null, Instant.now().plusSeconds(3600), "it").token();

        String projectA = unique("pa");
        String projectB = unique("pb");
        String connectionA = unique("ca");
        String connectionB = unique("cb");
        UUID operationA = UUID.randomUUID();
        UUID operationB = UUID.randomUUID();
        call("POST", API + "/ingest/operations", token, payloadAt("DEV", operationA, projectA, connectionA, uniqueUrl(), "update", "SUCCESS",
                Instant.now().minusSeconds(60), changeset("SUP-1", "EXECUTED")));
        call("POST", API + "/ingest/operations", token, payloadAt("DEV", operationB, projectB, connectionB, uniqueUrl(), "update", "SUCCESS",
                Instant.now().minusSeconds(60), changeset("SUP-2", "EXECUTED")));
        UUID idA = jdbc.sql("select id from project where slug = :s").param("s", projectA).query(UUID.class).single();
        UUID idB = jdbc.sql("select id from project where slug = :s").param("s", projectB).query(UUID.class).single();
        UUID connA = jdbc.sql("select id from db_connection where slug = :s").param("s", connectionA).query(UUID.class).single();
        UUID connB = jdbc.sql("select id from db_connection where slug = :s").param("s", connectionB).query(UUID.class).single();
        jdbc.sql("insert into project_connection (project_id, db_connection_id) values (:p, :c) on conflict do nothing")
                .param("p", idA).param("c", connA).update();
        jdbc.sql("insert into project_connection (project_id, db_connection_id) values (:p, :c) on conflict do nothing")
                .param("p", idB).param("c", connB).update();
        String pipelineA = json(callIn(workspace, "POST", API + "/pipelines", "{\"name\":\"%s\",\"projectId\":\"%s\"}".formatted(unique("PLA"), idA))).get("id").asString();
        String pipelineB = json(callIn(workspace, "POST", API + "/pipelines", "{\"name\":\"%s\",\"projectId\":\"%s\"}".formatted(unique("PLB"), idB))).get("id").asString();

        String ana = unique("ana");
        String bia = unique("bia");
        bind(ana, "PROJECT", idA.toString());
        bind(bia, "WORKSPACE", null);

        // Ana holds project A only.
        assertThat(names(callAs(ana, workspace, "GET", API + "/projects", null), "slug")).containsExactly(projectA);
        assertThat(names(callAs(ana, workspace, "GET", API + "/connections", null), "slug")).containsExactly(connectionA);
        assertThat(names(callAs(ana, workspace, "GET", API + "/pipelines", null), "id")).containsExactly(pipelineA);
        assertThat(names(callAs(ana, workspace, "GET", API + "/operations?size=50", null), "id")).containsExactly(operationA.toString());
        assertThat(callAs(ana, workspace, "GET", API + "/operations/" + operationA, null).statusCode()).isEqualTo(200);
        assertThat(callAs(ana, workspace, "GET", API + "/operations/" + operationB, null).statusCode()).as("another project's operation").isEqualTo(403);
        assertThat(callAs(ana, workspace, "GET", API + "/projects/" + idB, null).statusCode()).isEqualTo(403);
        assertThat(callAs(ana, workspace, "GET", API + "/connections/" + connB, null).statusCode()).isEqualTo(403);
        assertThat(callAs(ana, workspace, "GET", API + "/projects/" + idA, null).statusCode()).isEqualTo(200);

        // The dashboard of a restricted caller is over her scope and carries no workspace-wide bands.
        JsonNode dashboard = json(callAs(ana, workspace, "GET", API + "/dashboard", null));
        assertThat(dashboard.get("restricted").asBoolean()).isTrue();
        assertThat(dashboard.get("header").get("operations").asInt()).isEqualTo(1);
        assertThat(dashboard.has("policies")).isFalse();
        JsonNode whole = json(callAs(bia, workspace, "GET", API + "/dashboard", null));
        assertThat(whole.get("restricted").asBoolean()).isFalse();
        assertThat(whole.has("policies")).isTrue();

        // Policy exceptions follow their project; one about no project is the workspace's.
        UUID check = jdbc.sql("select id from policy_check limit 1").query(UUID.class).single();
        UUID exceptionA = UUID.randomUUID();
        UUID exceptionB = UUID.randomUUID();
        UUID exceptionGlobal = UUID.randomUUID();
        for (Object[] row : new Object[][] {{exceptionA, idA}, {exceptionB, idB}, {exceptionGlobal, null}}) {
            jdbc.sql("""
                    insert into policy_exception (id, workspace_id, check_id, project_id, justification, requested_by, status, expires_at)
                    values (:id, :ws, :check, :project, 'a reason long enough to be accepted', 'someone', 'REQUESTED', now() + interval '2 days')
                    """).param("id", row[0]).param("ws", workspaceId).param("check", check).param("project", row[1]).update();
        }
        assertThat(names(callAs(ana, workspace, "GET", API + "/policy-exceptions", null), "id"))
                .containsExactlyInAnyOrder(exceptionA.toString(), exceptionGlobal.toString());
        assertThat(callAs(ana, workspace, "GET", API + "/policy-exceptions/" + exceptionB, null).statusCode()).as("another project's exception").isEqualTo(403);
        assertThat(callAs(ana, workspace, "GET", API + "/policy-exceptions/" + exceptionA, null).statusCode()).isEqualTo(200);

        // The enforcement view of a pipeline is for whoever reads that pipeline.
        assertThat(callAs(ana, workspace, "GET", API + "/enforcement?pipeline=" + pipelineA, null).statusCode()).isEqualTo(200);
        assertThat(callAs(ana, workspace, "GET", API + "/enforcement?pipeline=" + pipelineB, null).statusCode()).as("another project's pipeline").isEqualTo(403);

        // Bia holds the whole workspace.
        assertThat(names(callAs(bia, workspace, "GET", API + "/projects", null), "slug")).contains(projectA, projectB);
        assertThat(names(callAs(bia, workspace, "GET", API + "/operations?size=50", null), "id")).contains(operationA.toString(), operationB.toString());
        assertThat(callAs(bia, workspace, "GET", API + "/operations/" + operationB, null).statusCode()).isEqualTo(200);

        // Somebody with no binding at all reads nothing.
        assertThat(callAs(unique("zed"), workspace, "GET", API + "/projects", null).statusCode()).isEqualTo(403);
    }
}
