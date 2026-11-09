package io.github.carloslimajlle.liquimanager.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** Somebody with no access asks for it; whoever administers access sees the request in the bell and in the list, and decides. */
class AccessRequestIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String workspace;
    private UUID workspaceId;

    private void scenario() {
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Access\"}".formatted(workspace))).get("id").asString());
        bind("admin.ana", "\"assignment:admin\"");
    }

    private String principal(String name, String type) {
        for (JsonNode found : json(callIn(workspace, "GET", API + "/principals?q=" + name, null))) {
            if (name.equals(found.get("externalId").asString())) {
                return found.get("id").asString();
            }
        }
        return json(callIn(workspace, "POST", API + "/principals",
                "{\"type\":\"%s\",\"externalId\":\"%s\",\"displayName\":\"%s\"}".formatted(type, name, name))).get("id").asString();
    }

    private void bind(String name, String permissions) {
        bind(name, "USER", permissions);
    }

    private void bind(String name, String type, String permissions) {
        HttpResponse<String> response = callIn(workspace, "POST", API + "/access/bindings",
                "{\"principalId\":\"%s\",\"permissions\":[%s,\"assignment:view\"],\"scopeType\":\"WORKSPACE\"}"
                        .formatted(principal(name, type), permissions));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private HttpResponse<String> as(String user, String method, String path, String body) {
        return callAs(user, workspace, method, API + path, body);
    }

    private JsonNode open() {
        return json(as("admin.ana", "GET", "/access-requests", null));
    }

    @Test
    @DisplayName("somebody with no access asks, once; asking again right away answers with the open request; the message is kept")
    void asksOnce() {
        scenario();

        HttpResponse<String> first = as("novo.dev", "POST", "/access-requests", "{\"message\":\"Sou do time de pagamentos\"}");

        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat(json(first).get("requester").asString()).isEqualTo("novo.dev");
        assertThat(json(first).get("message").asString()).isEqualTo("Sou do time de pagamentos");
        assertThat(json(first).get("status").asString()).isEqualTo("OPEN");

        HttpResponse<String> again = as("novo.dev", "POST", "/access-requests", "{\"message\":\"outra\"}");
        assertThat(again.statusCode()).as("too soon to ask again").isEqualTo(200);
        assertThat(json(again).get("already").asBoolean()).isTrue();
        assertThat(json(again).get("id").asString()).isEqualTo(json(first).get("id").asString());
        assertThat(json(again).get("message").asString()).as("the first message stays").isEqualTo("Sou do time de pagamentos");
        assertThat(open()).as("still one").hasSize(1);
    }

    @Test
    @DisplayName("a person sees their own request and nobody else's")
    void mineIsMine() {
        scenario();
        as("novo.dev", "POST", "/access-requests", null);

        assertThat(json(as("novo.dev", "GET", "/access-requests/mine", null)).get("status").asString()).isEqualTo("OPEN");
        assertThat(json(as("outro.dev", "GET", "/access-requests/mine", null)).get("id")).as("outro.dev asked for nothing").isNull();
    }

    @Test
    @DisplayName("only whoever administers access reads the list and sets a request aside")
    void onlyAdministratorsDecide() {
        scenario();
        String id = json(as("novo.dev", "POST", "/access-requests", null)).get("id").asString();

        assertThat(as("novo.dev", "GET", "/access-requests", null).statusCode()).as("the person cannot read the line").isEqualTo(403);
        assertThat(as("novo.dev", "POST", "/access-requests/" + id + "/dismiss", "{}").statusCode()).isEqualTo(403);

        JsonNode dismissed = json(as("admin.ana", "POST", "/access-requests/" + id + "/dismiss", "{\"note\":\"era engano\"}"));
        assertThat(dismissed.get("status").asString()).isEqualTo("DISMISSED");
        assertThat(dismissed.get("resolvedBy").asString()).isEqualTo("admin.ana");
        assertThat(dismissed.get("resolvedNote").asString()).isEqualTo("era engano");
        assertThat(open()).isEmpty();
        assertThat(as("admin.ana", "POST", "/access-requests/" + id + "/dismiss", "{}").statusCode()).as("already decided").isEqualTo(409);

        // a person whose request was set aside may ask again
        assertThat(as("novo.dev", "POST", "/access-requests", null).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("a request closes by itself when the person, or one of their groups, is granted anything")
    void grantedClosesIt() {
        scenario();
        as("novo.dev", "POST", "/access-requests", null);
        assertThat(open()).hasSize(1);

        bind("novo.dev", "\"deploy:dev\"");

        assertThat(open()).as("granted: nobody has to close it").isEmpty();
        assertThat(json(as("novo.dev", "GET", "/access-requests/mine", null)).get("id")).isNull();
        assertThat(json(as("admin.ana", "GET", "/access-requests?status=GRANTED", null))).hasSize(1);
    }

    @Test
    @DisplayName("granting the person's GROUP closes it too")
    void aGroupGrantClosesIt() {
        scenario();
        // the request of somebody who signed in with the group "dev-time" (the development chain cannot carry groups, so the row is written)
        jdbc.sql("""
                insert into access_request (id, workspace_id, requester, email, groups, status)
                values (:id, :ws, 'carla.dev', 'carla@example.com', '["dev-time"]', 'OPEN')
                """).param("id", UUID.randomUUID()).param("ws", workspaceId).update();
        assertThat(open()).hasSize(1);
        assertThat(open().get(0).get("groups")).extracting(JsonNode::asString).containsExactly("dev-time");

        bind("dev-time", "GROUP", "\"deploy:dev\"");

        assertThat(open()).isEmpty();
    }

    @Test
    @DisplayName("the bell of whoever administers access lists the request; the bell of anybody else does not")
    void theBell() {
        scenario();
        as("novo.dev", "POST", "/access-requests", null);

        JsonNode admin = json(as("admin.ana", "GET", "/me/pending", null));
        assertThat(admin.get("items")).extracting(item -> item.get("kind").asString()).contains("ACCESS");
        JsonNode item = admin.get("items").get(0);
        assertThat(item.get("requestedBy").asString()).isEqualTo("novo.dev");
        assertThat(item.get("decidable").asBoolean()).isTrue();

        bind("quem.le", "\"deploy:dev\"");
        assertThat(json(as("quem.le", "GET", "/me/pending", null)).get("items"))
                .extracting(i -> i.get("kind").asString()).doesNotContain("ACCESS");
    }

    @Test
    @DisplayName("an anonymous caller on the development chain has no identity to ask for")
    void needsAPerson() {
        scenario();

        HttpResponse<String> response = callIn(workspace, "POST", API + "/access-requests", "{}");

        assertThat(response.statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("a message that is too long is refused")
    void messageLimit() {
        scenario();

        HttpResponse<String> response = as("novo.dev", "POST", "/access-requests", "{\"message\":\"" + "x".repeat(501) + "\"}");

        assertThat(response.statusCode()).isEqualTo(422);
    }
}
