package io.github.carloslimajlle.liquimanager.changesets;

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

/**
 * What only a merge request has, grouped by merge request: listed in review until the merge brings it into the changelog, or until a person
 * closes the group (the merge request was closed or deleted) with a note.
 */
class InReviewIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";
    private static final Instant T0 = Instant.parse("2026-09-15T10:00:00Z");

    private String workspace;
    private String token;
    private String project;
    private String connection;
    private String url;
    private int tick;

    private void scenario() {
        workspace = unique("ws");
        UUID workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Review\"}".formatted(workspace))).get("id").asString());
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}");
        token = tokens.issue(workspaceId, unique("ing"), List.of("ingest"), null, Instant.now().plusSeconds(3600), "it").token();
        project = unique("proj");
        connection = unique("dev");
        url = uniqueUrl();
        tick = 0;
    }

    /** A pipeline report. {@code mr}: "iid|title" of the merge request it runs for, or null. */
    private void report(String branch, String mr, String... ids) {
        // a merge request's pipeline only looks (checks run: nothing is executed); main's update has already run what it lists
        String outcome = "main".equals(branch) ? "PREVIOUSLY_RUN" : "NOT_EXECUTED";
        String changesets = String.join(",", java.util.Arrays.stream(ids).map(id -> changeset(id, outcome)).toList());
        String ci = mr == null ? "" : "\"ci\": { \"provider\": \"gitlab\", \"pipelineId\": \"7\", \"mergeRequestIid\": \"" + mr.split("\\|")[0]
                + "\", \"mergeRequestTitle\": \"" + mr.split("\\|")[1] + "\", \"mergeRequestUrl\": \"http://git/-/merge_requests/"
                + mr.split("\\|")[0] + "\" },";
        String body = payloadAt("DEV", UUID.randomUUID(), project, connection, url, "update", "SUCCESS", T0.plusSeconds(60L * ++tick), changesets)
                .replace("\"format\": \"XML\" }", "\"format\": \"XML\", \"complete\": true }")
                .replace("\"branch\": \"main\"", "\"branch\": \"" + branch + "\"")
                .replace("\"runtime\":", ci + " \"runtime\":");
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token, body);
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    private String projectId() {
        return jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single().toString();
    }

    private JsonNode inReview(boolean closed) {
        return json(callIn(workspace, "GET", API + "/projects/" + projectId() + "/changesets/in-review?closed=" + closed, null));
    }

    private HttpResponse<String> close(String mrIid, String branch, String note) {
        return callIn(workspace, "POST", API + "/projects/" + projectId() + "/changesets/in-review/close",
                "{\"mrIid\":%s,\"branch\":%s,\"note\":%s}".formatted(quoted(mrIid), quoted(branch), quoted(note)));
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    @Test
    @DisplayName("what only merge requests have is listed grouped by merge request, with the title, the link and the last report")
    void groupedByMergeRequest() {
        scenario();
        report("main", null, "A");
        report("feat/x", "7|Adiciona X e Y", "A", "X", "Y");
        report("feat/z", "8|Adiciona Z", "A", "Z");

        JsonNode review = inReview(false);

        assertThat(review.get("openCount").asInt()).isEqualTo(2);
        JsonNode first = review.get("groups").get(0);
        assertThat(first.get("mrIid").asString()).as("the most recent report first").isEqualTo("8");
        assertThat(first.get("mrTitle").asString()).isEqualTo("Adiciona Z");
        assertThat(first.get("mrUrl").asString()).endsWith("/merge_requests/8");
        assertThat(first.get("branch").asString()).isEqualTo("feat/z");
        assertThat(first.get("lastSeenAt").asString()).isNotBlank();
        JsonNode seven = review.get("groups").get(1);
        assertThat(seven.get("changesets")).extracting(c -> c.get("changesetId").asString()).containsExactlyInAnyOrder("X", "Y");
        assertThat(seven.get("closed").asBoolean()).isFalse();

        // the Changesets tab is for what is in the changelog
        JsonNode list = json(callIn(workspace, "GET", API + "/projects/" + projectId() + "/changesets", null));
        assertThat(list.get("content")).extracting(c -> c.get("changesetId").asString()).containsExactly("A");
    }

    @Test
    @DisplayName("closing a group needs a note, hides it, keeps it with who, when and why, and can be undone")
    void closeWithANote() {
        scenario();
        report("main", null, "A");
        report("feat/x", "7|Adiciona X e Y", "A", "X", "Y");

        assertThat(close("7", null, "  ").statusCode()).as("a note is required").isEqualTo(422);
        assertThat(close("7", null, "abc").statusCode()).as("and it has to say something").isEqualTo(422);
        HttpResponse<String> closed = close("7", null, "MR reprovado pela revisao e deletado");
        assertThat(closed.statusCode()).as(closed.body()).isEqualTo(200);
        assertThat(json(closed).get("closed").asInt()).isEqualTo(2);

        assertThat(inReview(false).get("groups")).as("a closed group is not listed by default").isEmpty();
        JsonNode all = inReview(true);
        assertThat(all.get("openCount").asInt()).isZero();
        assertThat(all.get("closedCount").asInt()).isEqualTo(1);
        JsonNode group = all.get("groups").get(0);
        assertThat(group.get("closed").asBoolean()).isTrue();
        assertThat(group.get("closedNote").asString()).isEqualTo("MR reprovado pela revisao e deletado");
        assertThat(group.get("closedBy").asString()).isNotBlank();
        assertThat(group.get("closedAt").asString()).isNotBlank();
        assertThat(group.get("changesets")).hasSize(2);
        assertThat(close("7", null, "de novo").statusCode()).as("nothing open is left to close").isEqualTo(422);

        // nothing is deleted: the rows are still there, marked
        assertThat(jdbc.sql("select count(*) from changeset cs join changelog cl on cl.id = cs.changelog_id join project p on p.id = cl.project_id"
                + " where p.slug = :p and cs.dismissed_at is not null").param("p", project).query(Long.class).single()).isEqualTo(2);

        HttpResponse<String> reopened = callIn(workspace, "POST", API + "/projects/" + projectId() + "/changesets/in-review/reopen",
                "{\"mrIid\":\"7\",\"branch\":null,\"note\":null}");
        assertThat(reopened.statusCode()).as(reopened.body()).isEqualTo(200);
        assertThat(inReview(false).get("openCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a new report from a closed merge request means it is alive again")
    void aNewReportReopensIt() {
        scenario();
        report("main", null, "A");
        report("feat/x", "7|Adiciona X", "A", "X");
        assertThat(close("7", null, "fechado por engano").statusCode()).isEqualTo(200);
        assertThat(inReview(false).get("openCount").asInt()).isZero();

        report("feat/x", "7|Adiciona X", "A", "X");

        assertThat(inReview(false).get("openCount").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("once the merge request is merged its changesets are in the changelog and the group is gone")
    void mergedGroupsDisappear() {
        scenario();
        report("main", null, "A");
        report("feat/x", "7|Adiciona X e Y", "A", "X", "Y");
        report("feat/z", "8|Adiciona Z", "A", "Z");
        assertThat(inReview(false).get("openCount").asInt()).isEqualTo(2);

        report("main", null, "A", "X", "Y");

        JsonNode review = inReview(true);
        assertThat(review.get("groups")).extracting(g -> g.get("mrIid").asString()).containsExactly("8");
        assertThat(review.get("closedCount").asInt()).isZero();
        JsonNode list = json(callIn(workspace, "GET", API + "/projects/" + projectId() + "/changesets", null));
        assertThat(list.get("content")).extracting(c -> c.get("changesetId").asString()).containsExactlyInAnyOrder("A", "X", "Y");
    }

    @Test
    @DisplayName("a branch with no merge request is a group of its own, by branch name")
    void aBranchWithoutAMergeRequest() {
        scenario();
        report("main", null, "A");
        report("feat/sem-mr", null, "A", "W");

        JsonNode group = inReview(false).get("groups").get(0);

        assertThat(group.get("mrIid")).as("no merge request: null fields are omitted").isNull();
        assertThat(group.get("branch").asString()).isEqualTo("feat/sem-mr");
        assertThat(close(null, "feat/sem-mr", "branch abandonada").statusCode()).isEqualTo(200);
        assertThat(inReview(false).get("groups")).isEmpty();
    }
}
