package io.github.carloslimajlle.liquimanager.exceptions;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** The exception workflow in an isolated workspace, acting as different people. */
class PolicyExceptionIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";
    private static final String WHY = "Migracao legada, o rollback sera tratado no proximo release";

    private String workspace;
    private UUID workspaceId;
    private String project;
    private String token;

    @BeforeEach
    void scenario() {
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Exc\"}".formatted(workspace))).get("id").asString());
        for (String code : new String[]{"DEV", "PRD"}) {
            callIn(workspace, "POST", API + "/environments", "{\"code\":\"%s\",\"name\":\"%s\",\"sortOrder\":%d,\"isProduction\":%s}"
                    .formatted(code, code, code.equals("DEV") ? 1 : 2, code.equals("PRD")));
        }
        // Everyone who acts below needs the permission for what they do: RBAC applies to named users.
        for (String user : new String[]{"ana", "bruno", "carla", "p.ricardo"}) {
            // Principals belong to the identity provider, not to a workspace: reuse one that already exists.
            String principal = null;
            for (JsonNode found : json(callIn(workspace, "GET", API + "/principals?q=" + user, null))) {
                if (user.equals(found.get("externalId").asString())) {
                    principal = found.get("id").asString();
                }
            }
            if (principal == null) {
                principal = json(callIn(workspace, "POST", API + "/principals",
                        "{\"type\":\"USER\",\"externalId\":\"%s\",\"displayName\":\"%s\"}".formatted(user, user))).get("id").asString();
            }
            HttpResponse<String> bound = callIn(workspace, "POST", API + "/access/bindings", """
                    { "principalId": "%s", "permissions": ["assignment:view", "exception:request", "exception:approve"],
                      "scopeType": "WORKSPACE" }
                    """.formatted(principal));
            assertThat(bound.statusCode()).as(bound.body()).isEqualTo(201);
        }
        // Without a default package a workspace has no rules, and so nothing to be excepted from.
        callIn(workspace, "POST", API + "/policy-packages", "{\"name\":\"default\"}");
        project = unique("proj");
        token = tokens.issue(workspaceId, unique("exc"), List.of("ingest", "checks:read"), null,
                Instant.now().plusSeconds(3600), "integration-test").token();
        // The changeset SUP-9 was written by p.ricardo.
        HttpResponse<String> ingested = call("POST", API + "/ingest/operations", token,
                payloadAt("DEV", UUID.randomUUID(), project, unique("conn"), uniqueUrl(), "update", "SUCCESS",
                        Instant.now().minusSeconds(60), changeset("SUP-9", "EXECUTED")));
        assertThat(ingested.statusCode()).as(ingested.body()).isIn(200, 202);
    }

    private static String in(long days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private HttpResponse<String> as(String user, String method, String path, String body) {
        return callAs(user, workspace, method, API + path, body);
    }

    private JsonNode request(String user, String check, String changeset, String expiresAt) {
        HttpResponse<String> response = as(user, "POST", "/policy-exceptions", """
                { "check": "%s", "project": "%s", %s "justification": "%s", "expiresAt": "%s" }
                """.formatted(check, project, changeset == null ? "" : "\"changeset\": \"" + changeset + "\",", WHY, expiresAt));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response);
    }

    private JsonNode effective() {
        HttpResponse<String> response = call("GET", API + "/policies/effective?project=" + project + "&env=PRD", token, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    // --------------------------------------------------------------- request

    @Test
    @DisplayName("a request starts as REQUESTED, owned by the requester")
    void request() {
        JsonNode created = request("ana", "RollbackRequired", "SUP-9", in(7));

        assertThat(created.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(created.get("requestedBy").asString()).isEqualTo("ana");
        assertThat(created.get("changeset").asString()).isEqualTo("SUP-9");
        assertThat(created.get("changesetAuthor").asString()).isEqualTo("p.ricardo");
    }

    @Test
    @DisplayName("a bad request is refused: short justification, no expiry, too far, unknown check or changeset")
    void validation() {
        String base = "\"check\":\"RollbackRequired\",\"project\":\"%s\"".formatted(project);
        assertThat(as("ana", "POST", "/policy-exceptions",
                "{%s,\"justification\":\"curto\",\"expiresAt\":\"%s\"}".formatted(base, in(3))).statusCode()).isEqualTo(422);
        assertThat(as("ana", "POST", "/policy-exceptions",
                "{%s,\"justification\":\"%s\"}".formatted(base, WHY)).statusCode()).as("no expiry").isEqualTo(422);
        assertThat(as("ana", "POST", "/policy-exceptions",
                "{%s,\"justification\":\"%s\",\"expiresAt\":\"%s\"}".formatted(base, WHY, in(-1))).statusCode()).as("past").isEqualTo(422);
        assertThat(as("ana", "POST", "/policy-exceptions",
                "{%s,\"justification\":\"%s\",\"expiresAt\":\"%s\"}".formatted(base, WHY, in(400))).statusCode()).as("too far").isEqualTo(422);
        assertThat(as("ana", "POST", "/policy-exceptions",
                "{\"check\":\"NoSuchCheck\",\"justification\":\"%s\",\"expiresAt\":\"%s\"}".formatted(WHY, in(3))).statusCode())
                .isEqualTo(422);
        assertThat(as("ana", "POST", "/policy-exceptions",
                "{%s,\"changeset\":\"NOPE\",\"justification\":\"%s\",\"expiresAt\":\"%s\"}".formatted(base, WHY, in(3))).statusCode())
                .isEqualTo(422);
    }

    // ------------------------------------------------------------------- SoD

    @Test
    @DisplayName("SoD-2: the requester cannot approve their own exception (LQM-010)")
    void requesterCannotApprove() {
        JsonNode created = request("ana", "RollbackRequired", null, in(7));

        HttpResponse<String> response = as("ana", "POST", "/policy-exceptions/" + created.get("id").asString() + "/approve", null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("LQM-010");
    }

    @Test
    @DisplayName("SoD-1: the author of the changeset cannot approve an exception for it")
    void authorCannotApprove() {
        JsonNode created = request("ana", "RollbackRequired", "SUP-9", in(7));

        HttpResponse<String> response = as("p.ricardo", "POST", "/policy-exceptions/" + created.get("id").asString() + "/approve", null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("LQM-010");
    }

    @Test
    @DisplayName("somebody else approves, and the exception then reaches the collectors")
    void approvalReachesCollectors() {
        JsonNode created = request("ana", "RollbackRequired", "SUP-9", in(7));
        assertThat(effective().get("exceptions")).as("not before approval").isEmpty();

        HttpResponse<String> response = as("bruno", "POST", "/policy-exceptions/" + created.get("id").asString() + "/approve", null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("status").asString()).isEqualTo("APPROVED");
        assertThat(json(response).get("approvedBy").asString()).isEqualTo("bruno");
        JsonNode served = effective().get("exceptions");
        assertThat(served).hasSize(1);
        assertThat(served.get(0).get("check").asString()).isEqualTo("RollbackRequired");
        assertThat(served.get(0).get("changeset").asString()).isEqualTo("SUP-9");
    }

    // ------------------------------------------------------------- lifecycle

    @Test
    @DisplayName("an exception past its expiry cannot be approved (LQM-030)")
    void expiredCannotBeApproved() throws InterruptedException {
        HttpResponse<String> created = as("ana", "POST", "/policy-exceptions", """
                { "check": "RollbackRequired", "justification": "%s", "expiresAt": "%s" }
                """.formatted(WHY, Instant.now().plusSeconds(2)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode listed = json(as("ana", "GET", "/policy-exceptions", null)).get(0);
        Thread.sleep(2500);

        HttpResponse<String> response = as("bruno", "POST", "/policy-exceptions/" + listed.get("id").asString() + "/approve", null);

        assertThat(response.statusCode()).isEqualTo(409);
        assertThat(json(response).get("code").asString()).isEqualTo("LQM-030");
        assertThat(json(as("ana", "GET", "/policy-exceptions/" + listed.get("id").asString(), null)).get("status").asString())
                .isEqualTo("EXPIRED");
    }

    @Test
    @DisplayName("revoking stops the exception being served; rejecting never serves it")
    void revokeAndReject() {
        JsonNode first = request("ana", "RollbackRequired", null, in(7));
        as("bruno", "POST", "/policy-exceptions/" + first.get("id").asString() + "/approve", null);
        assertThat(effective().get("exceptions")).hasSize(1);

        HttpResponse<String> revoked = as("bruno", "POST", "/policy-exceptions/" + first.get("id").asString() + "/revoke", null);
        assertThat(json(revoked).get("status").asString()).isEqualTo("REVOKED");
        assertThat(effective().get("exceptions")).isEmpty();

        JsonNode second = request("ana", "DropColumnWarn", null, in(7));
        HttpResponse<String> rejected = as("bruno", "POST", "/policy-exceptions/" + second.get("id").asString() + "/reject", null);
        assertThat(json(rejected).get("status").asString()).isEqualTo("REJECTED");
        assertThat(effective().get("exceptions")).isEmpty();
    }

    @Test
    @DisplayName("a decision is final: it cannot be approved twice or approved after rejection")
    void decisionsAreFinal() {
        JsonNode created = request("ana", "RollbackRequired", null, in(7));
        String id = created.get("id").asString();
        as("bruno", "POST", "/policy-exceptions/" + id + "/reject", null);

        assertThat(as("carla", "POST", "/policy-exceptions/" + id + "/approve", null).statusCode()).isEqualTo(409);
        assertThat(as("carla", "POST", "/policy-exceptions/" + id + "/revoke", null).statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("the list filters by status")
    void listByStatus() {
        JsonNode a = request("ana", "RollbackRequired", null, in(7));
        request("ana", "DropColumnWarn", null, in(7));
        as("bruno", "POST", "/policy-exceptions/" + a.get("id").asString() + "/approve", null);

        assertThat(json(as("ana", "GET", "/policy-exceptions?status=APPROVED", null))).hasSize(1);
        assertThat(json(as("ana", "GET", "/policy-exceptions?status=REQUESTED", null))).hasSize(1);
        assertThat(json(as("ana", "GET", "/policy-exceptions", null))).hasSize(2);
    }

    @Test
    @DisplayName("request, approval and revocation are audited with who did it")
    void audited() {
        JsonNode created = request("ana", "RollbackRequired", null, in(7));
        String id = created.get("id").asString();
        as("bruno", "POST", "/policy-exceptions/" + id + "/approve", null);
        as("bruno", "POST", "/policy-exceptions/" + id + "/revoke", null);

        List<String> events = jdbc.sql("select action || ':' || actor from audit_event where target_id = :id order by seq")
                .param("id", id).query(String.class).list();

        assertThat(events).containsExactly("exception.request:ana", "exception.approve:bruno", "exception.revoke:bruno");
    }
}
