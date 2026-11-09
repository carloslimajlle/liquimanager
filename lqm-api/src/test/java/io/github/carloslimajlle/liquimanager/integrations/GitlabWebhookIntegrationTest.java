package io.github.carloslimajlle.liquimanager.integrations;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/**
 * GitLab tells LiquiManager that a merge request was closed or reopened: one webhook for the whole workspace (registered once, on the group),
 * the project found by the repository path in the payload.
 */
class GitlabWebhookIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private String workspace;
    private UUID workspaceId;
    private String ingestToken;
    private String webhookToken;
    private String project;
    private String projectId;
    private String connection;
    private String url;
    private int tick;

    private void scenario() {
        base();
        // a merge request that adds X and Y
        report(connection, url, "feat/x", "7", "NOT_EXECUTED", "A", "X", "Y");
    }

    /** The workspace, the project (known to GitLab as acme/<project>) and main, with nothing in review yet. */
    private void base() {
        tick = 0;
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Webhook\"}".formatted(workspace))).get("id").asString());
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}");
        ingestToken = tokens.issue(workspaceId, unique("ing"), List.of("ingest"), null, Instant.now().plusSeconds(3600), "it").token();
        webhookToken = tokens.issue(workspaceId, unique("hook"), List.of("webhook"), null, Instant.now().plusSeconds(3600), "it").token();
        project = unique("proj");
        connection = unique("dev");
        url = uniqueUrl();
        report(connection, url, "main", null, "PREVIOUSLY_RUN", "A");
        projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single().toString();
        // the project's repository, as GitLab names it (host and scheme do not matter)
        jdbc.sql("update project set repo_url = :url where slug = :s").param("url", "http://gitlab.local:8929/acme/" + project + ".git")
                .param("s", project).update();
    }

    private void report(String connection, String url, String branch, String mrIid, String outcome, String... ids) {
        String changesets = String.join(",", java.util.Arrays.stream(ids).map(id -> changeset(id, outcome)).toList());
        String ci = mrIid == null ? "" : "\"ci\": { \"provider\": \"gitlab\", \"pipelineId\": \"7\", \"mergeRequestIid\": \"" + mrIid
                + "\", \"mergeRequestTitle\": \"Adiciona X e Y\", \"mergeRequestUrl\": \"http://git/-/merge_requests/" + mrIid + "\" },";
        String body = payloadAt("DEV", UUID.randomUUID(), project, connection, url, "update", "SUCCESS",
                Instant.parse("2026-09-15T10:00:00Z").plusSeconds(60L * ++tick), changesets)
                .replace("\"format\": \"XML\" }", "\"format\": \"XML\", \"complete\": true }")
                .replace("\"branch\": \"main\"", "\"branch\": \"" + branch + "\"")
                .replace("\"runtime\":", ci + " \"runtime\":");
        HttpResponse<String> response = call("POST", API + "/ingest/operations", ingestToken, body);
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    /** What GitLab sends: the token in X-Gitlab-Token, not an Authorization header. */
    private HttpResponse<String> gitlab(String token, String body) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + API + "/integrations/gitlab/webhook"))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
            if (token != null) {
                request.header("X-Gitlab-Token", token);
            }
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String event(String action, String iid, String path) {
        return event(action, iid, path, null);
    }

    /** {@code updatedAt}: when GitLab says the merge request changed ("2026-11-05 00:37:30 UTC"); null leaves it out. */
    private static String event(String action, String iid, String path, String updatedAt) {
        return """
                {"object_kind":"merge_request","event_type":"merge_request",
                 "user":{"username":"ana"},
                 "project":{"id":42,"path_with_namespace":"%s","web_url":"http://elsewhere.example/%s"},
                 "object_attributes":{"iid":%s,"action":"%s","state":"%s"%s}}
                """.formatted(path, path, iid, action, switch (action) {
                    case "close" -> "closed";
                    case "merge" -> "merged";
                    default -> "opened";
                }, updatedAt == null ? "" : ",\"updated_at\":\"" + updatedAt + "\"");
    }

    private JsonNode inReview(boolean closed) {
        return json(callIn(workspace, "GET", API + "/projects/" + projectId + "/changesets/in-review?closed=" + closed, null));
    }

    @Test
    @DisplayName("a merge request closed in GitLab closes its group here, with a note that says so, and reopened opens it again")
    void closedAndReopened() {
        scenario();
        assertThat(inReview(false).get("openCount").asInt()).isEqualTo(1);

        HttpResponse<String> closed = gitlab(webhookToken, event("close", "7", "acme/" + project));

        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        assertThat(json(closed).get("handled").asBoolean()).isTrue();
        assertThat(json(closed).get("changesets").asInt()).isEqualTo(2);
        assertThat(inReview(false).get("groups")).isEmpty();
        JsonNode group = inReview(true).get("groups").get(0);
        assertThat(group.get("closedBy").asString()).isEqualTo("gitlab:ana");
        assertThat(group.get("closedNote").asString()).contains("MR !7").contains("GitLab").contains("ana");

        HttpResponse<String> reopened = gitlab(webhookToken, event("reopen", "7", "acme/" + project));
        assertThat(json(reopened).get("changesets").asInt()).isEqualTo(2);
        assertThat(inReview(false).get("openCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("it answers 200 to what it does not do, so GitLab does not disable the webhook")
    void ignoresWhatItDoesNotCareAbout() {
        scenario();

        assertThat(json(gitlab(webhookToken, event("merge", "7", "acme/" + project))).get("handled").asBoolean())
                .as("a merge needs nothing: the next report from main brings the changesets in").isFalse();
        assertThat(json(gitlab(webhookToken, event("update", "7", "acme/" + project))).get("handled").asBoolean()).isFalse();
        JsonNode unknown = json(gitlab(webhookToken, event("close", "7", "acme/another-repository")));
        assertThat(unknown.get("handled").asBoolean()).as("a repository no project here has").isFalse();
        assertThat(json(gitlab(webhookToken, "{\"object_kind\":\"push\"}")).get("handled").asBoolean()).isFalse();
        assertThat(inReview(false).get("openCount").asInt()).as("nothing was closed").isEqualTo(1);
    }

    @Test
    @DisplayName("without the token, or with one that has another scope, GitLab is refused")
    void needsTheWebhookToken() {
        scenario();

        assertThat(gitlab(null, event("close", "7", "acme/" + project)).statusCode()).isEqualTo(401);
        assertThat(gitlab("lqm_not_a_token", event("close", "7", "acme/" + project)).statusCode()).isEqualTo(401);
        assertThat(gitlab(ingestToken, event("close", "7", "acme/" + project)).statusCode()).as("an ingest token is not a webhook token").isEqualTo(403);
        assertThat(inReview(false).get("openCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a token limited to some projects speaks only for them")
    void aLimitedTokenSpeaksForItsProjects() {
        scenario();
        String other = tokens.issue(workspaceId, unique("hook"), List.of("webhook"), List.of(UUID.randomUUID()),
                Instant.now().plusSeconds(3600), "it").token();

        JsonNode answer = json(gitlab(other, event("close", "7", "acme/" + project)));

        assertThat(answer.get("handled").asBoolean()).as("the project is not one of its projects").isFalse();
        assertThat(inReview(false).get("openCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a merge request closed before its pipeline reported: the report that comes later is born closed")
    void closedBeforeThePipelineReported() {
        base();

        // GitLab says "closed" when there is nothing of the merge request here yet
        JsonNode answer = json(gitlab(webhookToken, event("close", "9", "acme/" + project)));
        assertThat(answer.get("handled").asBoolean()).isTrue();
        assertThat(answer.get("changesets").asInt()).as("nothing to close yet").isZero();

        // its pipeline reports a little later
        report(connection, url, "feat/y", "9", "NOT_EXECUTED", "A", "Y");

        assertThat(inReview(false).get("groups")).as("it is not listed as in review").isEmpty();
        JsonNode group = inReview(true).get("groups").get(0);
        assertThat(group.get("closed").asBoolean()).isTrue();
        assertThat(group.get("closedBy").asString()).isEqualTo("gitlab:ana");
        assertThat(group.get("closedNote").asString()).contains("MR !9").contains("fechado");

        // reopened in GitLab and reporting again: alive
        gitlab(webhookToken, event("reopen", "9", "acme/" + project));
        report(connection, url, "feat/y", "9", "NOT_EXECUTED", "A", "Y");
        assertThat(inReview(false).get("openCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("GitLab delivers asynchronously: the creation event of a merge request, arriving after its close, does not undo it")
    void aLateCreationEventDoesNotUndoTheClose() {
        base();
        // GitLab builds the payload when its job runs, so the creation event can carry a time LATER than the close that followed it
        String closed = "2026-11-05T00:37:30.000Z";
        String creation = "2026-11-05T00:37:31.000Z";

        assertThat(json(gitlab(webhookToken, event("close", "9", "acme/" + project, closed))).get("handled").asBoolean()).isTrue();
        assertThat(json(gitlab(webhookToken, event("open", "9", "acme/" + project, creation))).get("handled").asBoolean()).isFalse();

        // so the report of its pipeline, a little later, still finds it closed
        report(connection, url, "feat/y", "9", "NOT_EXECUTED", "A", "Y");
        assertThat(inReview(false).get("groups")).isEmpty();
        assertThat(inReview(true).get("closedCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("in the usual order (creation, then close) the close still wins, whatever times the payloads carry")
    void theCloseWinsInTheUsualOrderToo() {
        scenario();
        assertThat(json(gitlab(webhookToken, event("open", "7", "acme/" + project, "2026-11-05T00:37:31.000Z"))).get("handled").asBoolean()).isFalse();

        JsonNode closed = json(gitlab(webhookToken, event("close", "7", "acme/" + project, "2026-11-05T00:37:30.000Z")));

        assertThat(closed.get("handled").asBoolean()).isTrue();
        assertThat(closed.get("changesets").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("a 'reopen' that is older than the 'close' it follows in the event time does not reopen what was closed later")
    void aStaleReopenIsIgnored() {
        scenario();
        gitlab(webhookToken, event("close", "7", "acme/" + project, "2026-11-05T00:37:30.000Z"));

        JsonNode stale = json(gitlab(webhookToken, event("reopen", "7", "acme/" + project, "2026-11-05T00:37:29.000Z")));

        assertThat(stale.get("handled").asBoolean()).isFalse();
        assertThat(stale.get("reason").asString()).contains("newer event");
        assertThat(inReview(false).get("groups")).isEmpty();
    }
}
