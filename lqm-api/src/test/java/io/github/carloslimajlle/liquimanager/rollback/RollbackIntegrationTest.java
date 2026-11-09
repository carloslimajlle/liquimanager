package io.github.carloslimajlle.liquimanager.rollback;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/**
 * Targeted rollback: the request, who may decide it, and what a runner is told. The changeset SUP-9 was written by
 * p.ricardo and ran in DEV and in PRD; SUP-NO has no rollback.
 */
class RollbackIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";
    private static final String WHY = "Regressao em producao, voltar a mudanca";

    private String workspace;
    private UUID workspaceId;
    private String project;
    private String devOperation;
    private String prdOperation;
    private String prdConnection;
    private String token;

    @BeforeEach
    void scenario() {
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Rollback\"}".formatted(workspace))).get("id").asString());
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}");
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"PRD\",\"name\":\"PRD\",\"sortOrder\":2,\"isProduction\":true}");
        project = unique("proj");
        prdConnection = unique("prd");
        String token = tokens.issue(workspaceId, unique("ing"), List.of("ingest"), null, Instant.now().plusSeconds(3600), "it").token();

        // In PRD SUP-NO (no rollback) ran first and SUP-9 after it: going back to SUP-9 undoes only SUP-9; going back to SUP-NO undoes both.
        String noRollback = changeset("SUP-NO", "EXECUTED", 1).replace("\"hasRollback\": true", "\"hasRollback\": false");
        devOperation = ingest(token, "DEV", unique("dev"), changeset("SUP-9", "EXECUTED"));
        prdOperation = ingest(token, "PRD", prdConnection, noRollback + "," + changeset("SUP-9", "EXECUTED", 2));
        this.token = token;

        for (String[] person : new String[][] {
                {"ana", "rollback:prd,rollback:dev"}, {"bruno", "rollback:approve"},
                {"p.ricardo", "rollback:approve"}, {"carla", "assignment:view"}}) {
            bind(person[0], List.of(person[1].split(",")));
        }
    }

    private String ingest(String token, String env, String connection, String changesets) {
        UUID id = UUID.randomUUID();
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token,
                payloadAt(env, id, project, connection, uniqueUrl(), "update", "SUCCESS", Instant.now().minusSeconds(120), changesets));
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
        return id.toString();
    }

    private void bind(String user, List<String> permissions) {
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
        String perms = permissions.stream().map(p -> "\"" + p + "\"").reduce((a, b) -> a + "," + b).orElse("");
        HttpResponse<String> response = callIn(workspace, "POST", API + "/access/bindings",
                "{\"principalId\":\"%s\",\"permissions\":[%s,\"assignment:view\"],\"scopeType\":\"WORKSPACE\"}".formatted(principal, perms));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private HttpResponse<String> as(String user, String method, String path, String body) {
        return callAs(user, workspace, method, API + path, body);
    }

    private JsonNode requested(String user, String operation, String body) {
        HttpResponse<String> response = as(user, "POST", "/operations/" + operation + "/rollback-requests", body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response);
    }

    private String runnerToken() {
        return tokens.issue(workspaceId, unique("runner"), List.of("runner", "ingest"), null,
                Instant.now().plusSeconds(3600), "it").token();
    }

    private static String byChangeset(String id) {
        return "{\"changeset\":\"%s\",\"reason\":\"%s\"}".formatted(id, WHY);
    }

    // ------------------------------------------------------------------ requesting

    @Test
    @DisplayName("outside production a request is approved at once, by the system")
    void nonProductionAutoApproves() {
        JsonNode request = requested("ana", devOperation, byChangeset("SUP-9"));

        assertThat(request.get("status").asString()).isEqualTo("APPROVED");
        assertThat(request.get("approvedBy").asString()).isEqualTo("auto");
        assertThat(request.get("environment").asString()).isEqualTo("DEV");
    }

    @Test
    @DisplayName("in production it waits for somebody else")
    void productionWaits() {
        JsonNode request = requested("ana", prdOperation, byChangeset("SUP-9"));

        assertThat(request.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(request.get("approvedBy")).isNull();
        assertThat(request.get("changesetAuthor").asString()).isEqualTo("p.ricardo");
    }

    @Test
    @DisplayName("the caller needs the rollback permission of the environment")
    void needsPermission() {
        assertThat(as("carla", "POST", "/operations/" + prdOperation + "/rollback-requests", byChangeset("SUP-9")).statusCode())
                .isEqualTo(403);
        assertThat(as("bruno", "POST", "/operations/" + prdOperation + "/rollback-requests", byChangeset("SUP-9")).statusCode())
                .as("approving is not requesting").isEqualTo(403);
    }

    @Test
    @DisplayName("a changeset with no rollback cannot be requested (LQM-040)")
    void noRollbackNoRequest() {
        HttpResponse<String> response = as("ana", "POST", "/operations/" + prdOperation + "/rollback-requests", byChangeset("SUP-NO"));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(json(response).get("code").asString()).isEqualTo("LQM-040");
    }

    @Test
    @DisplayName("what is malformed is refused")
    void validation() {
        String path = "/operations/" + prdOperation + "/rollback-requests";
        assertThat(as("ana", "POST", path, "{\"reason\":\"" + WHY + "\"}").statusCode()).as("neither").isEqualTo(422);
        assertThat(as("ana", "POST", path, "{\"changeset\":\"SUP-9\",\"tag\":\"t\",\"reason\":\"" + WHY + "\"}").statusCode())
                .as("both").isEqualTo(422);
        assertThat(as("ana", "POST", path, "{\"changeset\":\"SUP-9\",\"reason\":\"curto\"}").statusCode()).as("reason").isEqualTo(422);
        assertThat(as("ana", "POST", path, byChangeset("NOPE")).statusCode()).as("not in the operation").isEqualTo(422);
        assertThat(as("ana", "POST", "/operations/" + UUID.randomUUID() + "/rollback-requests", byChangeset("SUP-9")).statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a request that waits for approval notifies the people who asked to hear about it")
    void pendingApprovalNotifies() {
        String addressee = "approvers-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        assertThat(callIn(workspace, "POST", API + "/notification-rules", """
                { "name": "approvers", "event": "ROLLBACK_PENDING", "channel": "EMAIL", "target": "%s" }
                """.formatted(addressee)).statusCode()).isEqualTo(201);

        requested("ana", devOperation, byChangeset("SUP-9"));
        long none = jdbc.sql("select count(*) from notification_delivery d join notification_rule r on r.id = d.rule_id "
                + "where r.workspace_id = :ws").param("ws", workspaceId).query(Long.class).single();
        assertThat(none).as("outside production nobody has to approve").isZero();

        JsonNode request = requested("ana", prdOperation, byChangeset("SUP-9"));

        String subject = jdbc.sql("select d.subject from notification_delivery d join notification_rule r on r.id = d.rule_id "
                + "where r.workspace_id = :ws and d.dedupe_key = :key").param("ws", workspaceId)
                .param("key", "rollback:" + request.get("id").asString()).query(String.class).single();
        assertThat(subject).contains("aguardando aprovacao");
    }

    @Test
    @DisplayName("a rollback to a tag is requested the same way")
    void tagRequest() {
        JsonNode request = requested("ana", devOperation, "{\"tag\":\"release-1.0.0\",\"reason\":\"" + WHY + "\"}");

        assertThat(request.get("kind").asString()).isEqualTo("TAG");
        assertThat(request.get("tag").asString()).isEqualTo("release-1.0.0");
    }

    // -------------------------------------------------------------------- deciding

    @Test
    @DisplayName("SoD-2: the requester cannot approve; SoD-1: neither can the author of the changeset")
    void separationOfDuties() {
        JsonNode request = requested("ana", prdOperation, byChangeset("SUP-9"));
        String path = "/rollback-requests/" + request.get("id").asString() + "/approve";
        bind("ana", List.of("rollback:approve"));

        HttpResponse<String> own = as("ana", "POST", path, null);
        assertThat(own.statusCode()).isEqualTo(409);
        assertThat(json(own).get("code").asString()).isEqualTo("LQM-010");

        HttpResponse<String> author = as("p.ricardo", "POST", path, null);
        assertThat(author.statusCode()).isEqualTo(409);
        assertThat(json(author).get("code").asString()).isEqualTo("LQM-010");
        assertThat(author.body()).contains("SoD-1");
    }

    @Test
    @DisplayName("somebody else with the permission approves; one without it cannot")
    void approval() {
        JsonNode request = requested("ana", prdOperation, byChangeset("SUP-9"));
        String path = "/rollback-requests/" + request.get("id").asString() + "/approve";

        assertThat(as("carla", "POST", path, null).statusCode()).isEqualTo(403);
        HttpResponse<String> approved = as("bruno", "POST", path, null);

        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(json(approved).get("status").asString()).isEqualTo("APPROVED");
        assertThat(json(approved).get("approvedBy").asString()).isEqualTo("bruno");
    }

    @Test
    @DisplayName("a rejected request stays rejected, and only the requester can cancel")
    void rejectAndCancel() {
        JsonNode rejected = requested("ana", prdOperation, byChangeset("SUP-9"));
        assertThat(json(as("bruno", "POST", "/rollback-requests/" + rejected.get("id").asString() + "/reject", null))
                .get("status").asString()).isEqualTo("REJECTED");
        assertThat(as("bruno", "POST", "/rollback-requests/" + rejected.get("id").asString() + "/approve", null).statusCode())
                .isEqualTo(409);

        JsonNode mine = requested("ana", prdOperation, byChangeset("SUP-9"));
        String cancel = "/rollback-requests/" + mine.get("id").asString() + "/cancel";
        assertThat(as("carla", "POST", cancel, null).statusCode()).isEqualTo(403);
        assertThat(json(as("ana", "POST", cancel, null)).get("status").asString()).isEqualTo("CANCELED");
    }

    // ---------------------------------------------------------------------- runner

    @Test
    @DisplayName("a runner claims the approved request and is told where and what, never a credential")
    void claim() {
        JsonNode request = requested("ana", devOperation, byChangeset("SUP-9"));
        String runner = runnerToken();

        HttpResponse<String> claimed = call("POST", API + "/runner/claim", runner, null);

        assertThat(claimed.statusCode()).as(claimed.body()).isEqualTo(200);
        JsonNode job = json(claimed);
        assertThat(job.get("id").asString()).isEqualTo(request.get("id").asString());
        assertThat(job.get("kind").asString()).isEqualTo("CHANGESET");
        assertThat(job.get("changeset").get("id").asString()).isEqualTo("SUP-9");
        assertThat(job.get("changeset").get("author").asString()).isEqualTo("p.ricardo");
        assertThat(job.get("parentOperationId").asString()).isEqualTo(devOperation);
        assertThat(job.get("environment").asString()).isEqualTo("DEV");
        assertThat(job.get("project").asString()).isEqualTo(project);
        assertThat(job.get("changelogPath").asString()).isNotBlank();
        assertThat(job.get("gitCommit").asString()).isNotBlank();
        assertThat(claimed.body()).doesNotContain("password").doesNotContain("secret");
        assertThat(json(as("ana", "GET", "/rollback-requests/" + request.get("id").asString(), null)).get("status").asString())
                .isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("nothing approved means nothing to claim, and a request is claimed once")
    void claimOnce() {
        String runner = runnerToken();
        assertThat(call("POST", API + "/runner/claim", runner, null).statusCode()).isEqualTo(204);

        requested("ana", devOperation, byChangeset("SUP-9"));
        assertThat(call("POST", API + "/runner/claim", runner, null).statusCode()).isEqualTo(200);
        assertThat(call("POST", API + "/runner/claim", runner, null).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("a request waiting for approval is not claimable")
    void unapprovedIsNotClaimable() {
        requested("ana", prdOperation, byChangeset("SUP-9"));

        assertThat(call("POST", API + "/runner/claim", runnerToken(), null).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("the runner reports success with the operation it produced, or failure with the reason")
    void complete() {
        JsonNode first = requested("ana", devOperation, byChangeset("SUP-9"));
        String runner = runnerToken();
        call("POST", API + "/runner/claim", runner, null);
        UUID result = UUID.randomUUID();

        HttpResponse<String> done = call("POST", API + "/runner/requests/" + first.get("id").asString() + "/complete", runner,
                "{\"ok\":true,\"resultOperationId\":\"" + result + "\"}");

        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        JsonNode stored = json(as("ana", "GET", "/rollback-requests/" + first.get("id").asString(), null));
        assertThat(stored.get("status").asString()).isEqualTo("DONE");
        assertThat(stored.get("resultOperationId").asString()).isEqualTo(result.toString());

        JsonNode second = requested("ana", devOperation, "{\"tag\":\"t1\",\"reason\":\"" + WHY + "\"}");
        call("POST", API + "/runner/claim", runner, null);
        call("POST", API + "/runner/requests/" + second.get("id").asString() + "/complete", runner,
                "{\"ok\":false,\"error\":\"changeset was never applied\"}");
        JsonNode failed = json(as("ana", "GET", "/rollback-requests/" + second.get("id").asString(), null));
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("error").asString()).contains("never applied");
    }

    @Test
    @DisplayName("only the runner that claimed it can finish it, and only while it is running")
    void completeGuards() {
        JsonNode request = requested("ana", devOperation, byChangeset("SUP-9"));
        String path = "/runner/requests/" + request.get("id").asString() + "/complete";
        String owner = runnerToken();
        String stranger = runnerToken();

        assertThat(call("POST", API + path, owner, "{\"ok\":true}").statusCode()).as("not running yet").isEqualTo(409);
        call("POST", API + "/runner/claim", owner, null);
        assertThat(call("POST", API + path, stranger, "{\"ok\":true}").statusCode()).isEqualTo(403);
        assertThat(call("POST", API + path, owner, "{\"ok\":true}").statusCode()).isEqualTo(200);
        assertThat(call("POST", API + path, owner, "{\"ok\":true}").statusCode()).as("finished already").isEqualTo(409);
    }

    @Test
    @DisplayName("a token without the runner scope cannot reach the runner endpoints")
    void runnerScope() {
        String ingestOnly = tokens.issue(workspaceId, unique("x"), List.of("ingest"), null, Instant.now().plusSeconds(3600), "it").token();

        assertThat(call("POST", API + "/runner/claim", ingestOnly, null).statusCode()).isEqualTo(403);
        assertThat(call("POST", API + "/runner/claim", null, null).statusCode()).isEqualTo(401);
    }

    @org.springframework.beans.factory.annotation.Autowired
    RollbackJobs jobs;

    @Test
    @DisplayName("a request a runner took and never finished is failed after 30 minutes, with the reason")
    void reaper() {
        JsonNode request = requested("ana", devOperation, byChangeset("SUP-9"));
        call("POST", API + "/runner/claim", runnerToken(), null);
        jobs.reap();
        assertThat(json(as("ana", "GET", "/rollback-requests/" + request.get("id").asString(), null)).get("status").asString())
                .as("just claimed, not reaped").isEqualTo("RUNNING");

        jdbc.sql("update rollback_request set claimed_at = now() - interval '31 minutes' where id = cast(:id as uuid)")
                .param("id", request.get("id").asString()).update();

        assertThat(jobs.reap()).isGreaterThanOrEqualTo(1);
        JsonNode stored = json(as("ana", "GET", "/rollback-requests/" + request.get("id").asString(), null));
        assertThat(stored.get("status").asString()).isEqualTo("FAILED");
        assertThat(stored.get("error").asString()).contains("30 minutes");
    }

    // ---------------------------------------------------------------------- reading

    @Test
    @DisplayName("the list filters by operation and status, and every step is audited")
    void listAndAudit() {
        JsonNode request = requested("ana", prdOperation, byChangeset("SUP-9"));
        requested("ana", devOperation, byChangeset("SUP-9"));
        as("bruno", "POST", "/rollback-requests/" + request.get("id").asString() + "/approve", null);

        assertThat(json(as("ana", "GET", "/rollback-requests?operation=" + prdOperation, null))).hasSize(1);
        assertThat(json(as("ana", "GET", "/rollback-requests?status=APPROVED", null))).hasSize(2);

        List<String> events = jdbc.sql("select action || ':' || actor from audit_event where target_id = :id order by seq")
                .param("id", request.get("id").asString()).query(String.class).list();
        assertThat(events).containsExactly("rollback.request:ana", "rollback.approve:bruno");
    }

    // ------------------------------------------------------------------ going back to a changeset

    /** A chain on its own connection: A, B and C ran in this order, all of them reversible. */
    private String chain() {
        return ingest(token, "DEV", unique("chain"),
                changeset("CH-A", "EXECUTED", 1) + "," + changeset("CH-B", "EXECUTED", 2) + "," + changeset("CH-C", "EXECUTED", 3));
    }

    @Test
    @DisplayName("going back to a changeset undoes it and everything after it, newest first, and says so in the request")
    void goingBackUndoesTheTail() {
        String operation = chain();

        JsonNode request = requested("ana", operation, byChangeset("CH-B"));

        assertThat(request.get("count").asInt()).isEqualTo(2);
        assertThat(request.get("plan")).extracting(item -> item.get("changesetId").asString()).containsExactly("CH-C", "CH-B");
    }

    @Test
    @DisplayName("the preview lists what would be undone before anybody asks for it")
    void previewListsTheTail() {
        String operation = chain();

        JsonNode preview = json(as("ana", "GET", "/operations/" + operation + "/rollback-preview?changeset=CH-A", null));

        assertThat(preview.get("total").asInt()).isEqualTo(3);
        assertThat(preview.get("reversible").asBoolean()).isTrue();
        assertThat(preview.get("items")).extracting(item -> item.get("changesetId").asString()).containsExactly("CH-C", "CH-B", "CH-A");
        assertThat(preview.get("items").get(2).get("target").asBoolean()).isTrue();
        assertThat(preview.get("items").get(0).get("target").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("going back over a changeset with no rollback is refused, naming it (LQM-040)")
    void goingBackOverAnIrreversibleOne() {
        JsonNode preview = json(as("ana", "GET", "/operations/" + prdOperation + "/rollback-preview?changeset=SUP-NO", null));
        assertThat(preview.get("reversible").asBoolean()).isFalse();
        assertThat(preview.get("blockers")).extracting(b -> b.asString()).containsExactly("SUP-NO");

        HttpResponse<String> response = as("ana", "POST", "/operations/" + prdOperation + "/rollback-requests", byChangeset("SUP-NO"));
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(json(response).get("code").asString()).isEqualTo("LQM-040");
        assertThat(response.body()).contains("SUP-NO");
    }

    @Test
    @DisplayName("a changeset the operation does not know has nothing to go back to")
    void unknownChangeset() {
        String operation = chain();
        HttpResponse<String> response = as("ana", "GET", "/operations/" + operation + "/rollback-preview?changeset=NOPE", null);
        assertThat(response.statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("the runner is handed the plan, newest first, as id::author::filePath")
    void runnerGetsThePlan() {
        String operation = chain();
        requested("ana", operation, byChangeset("CH-B"));

        JsonNode job = json(call("POST", API + "/runner/claim", runnerToken(), null));

        assertThat(job.get("plan")).extracting(JsonNode::asString)
                .containsExactly("CH-C::p.ricardo::db/changelog/work/CH-C.xml", "CH-B::p.ricardo::db/changelog/work/CH-B.xml");
    }

    @Test
    @DisplayName("a connection lists what is applied on it, newest first, with the operation that applied each, and its requests can be filtered")
    void appliedChangesetsOfAConnection() {
        String operation = chain();
        String connection = jdbc.sql("select db_connection_id from operation where id = :id").param("id", UUID.fromString(operation))
                .query(UUID.class).single().toString();

        JsonNode applied = json(as("ana", "GET", "/connections/" + connection + "/applied-changesets", null));

        assertThat(applied.get("environment").asString()).isEqualTo("DEV");
        assertThat(applied.get("items")).extracting(item -> item.get("changesetId").asString()).containsExactly("CH-C", "CH-B", "CH-A");
        assertThat(applied.get("items").get(0).get("operationId").asString()).isEqualTo(operation);

        requested("ana", operation, byChangeset("CH-B"));
        assertThat(json(as("ana", "GET", "/rollback-requests?connection=" + connection, null))).hasSize(1);
        assertThat(json(as("ana", "GET", "/rollback-requests?connection=" + UUID.randomUUID(), null))).isEmpty();
    }
}
