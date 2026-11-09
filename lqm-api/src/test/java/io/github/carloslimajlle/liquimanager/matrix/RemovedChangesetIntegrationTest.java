package io.github.carloslimajlle.liquimanager.matrix;

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
 * A changeset whose file was removed from the changelog on the default branch: LiquiManager notices it from the next complete report,
 * and the matrix and the promotion dialog stop treating it as something waiting to be promoted.
 */
class RemovedChangesetIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    // ------------------------------------------------------------------ noticing it

    private String token(String workspace) {
        UUID workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Removed\"}".formatted(workspace))).get("id").asString());
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}");
        return tokens.issue(workspaceId, unique("ing"), List.of("ingest"), null, Instant.now().plusSeconds(3600), "it").token();
    }

    /** A report of the changelog at one commit. {@code complete}: the collector classified all of it. */
    private void report(String token, String project, String connection, String url, Instant at, String branch, boolean complete,
                        String... ids) {
        reportFromMr(token, project, connection, url, at, branch, complete, null, ids);
    }

    /** {@code mr}: "iid|title" of the merge request the pipeline runs for, or null. */
    private void reportFromMr(String token, String project, String connection, String url, Instant at, String branch, boolean complete,
                              String mr, String... ids) {
        String changesets = String.join(",", java.util.Arrays.stream(ids).map(id -> changeset(id, "PREVIOUSLY_RUN")).toList());
        String ci = mr == null ? "" : "\"ci\": { \"provider\": \"gitlab\", \"pipelineId\": \"7\", \"mergeRequestIid\": \"" + mr.split("\\|")[0]
                + "\", \"mergeRequestTitle\": \"" + mr.split("\\|")[1] + "\", \"mergeRequestUrl\": \"http://git/-/merge_requests/"
                + mr.split("\\|")[0] + "\" },";
        String body = payloadAt("DEV", UUID.randomUUID(), project, connection, url, "update", "SUCCESS", at, changesets)
                .replace("\"format\": \"XML\" }", "\"format\": \"XML\"" + (complete ? ", \"complete\": true" : "") + " }")
                .replace("\"branch\": \"main\"", "\"branch\": \"" + branch + "\"")
                .replace("\"runtime\":", ci + " \"runtime\":");
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token, body);
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    private boolean removed(String project, String csId) {
        return jdbc.sql("""
                select cs.removed_at is not null from changeset cs join changelog cl on cl.id = cs.changelog_id
                  join project p on p.id = cl.project_id where p.slug = :p and cs.cs_id = :c
                """).param("p", project).param("c", csId).query(Boolean.class).single();
    }

    private String seenOnDefault(String project, String csId) {
        return jdbc.sql("""
                select coalesce(cs.seen_on_default_at::text, 'never') from changeset cs join changelog cl on cl.id = cs.changelog_id
                  join project p on p.id = cl.project_id where p.slug = :p and cs.cs_id = :c
                """).param("p", project).param("c", csId).query(String.class).single();
    }

    @Test
    @DisplayName("what only a merge request's branch has is not part of the changelog yet, says which merge request, and is never 'removed'")
    void onlyInAMergeRequest() {
        String workspace = unique("ws");
        String token = token(workspace);
        String project = unique("proj");
        String connection = unique("dev");
        String url = uniqueUrl();
        Instant t0 = Instant.parse("2026-09-15T10:00:00Z");
        report(token, project, connection, url, t0, "main", true, "A", "B");

        // a rename under review: the merge request's pipeline sees "B2" (and not B), main has never had it
        reportFromMr(token, project, connection, url, t0.plusSeconds(60), "chore/rename-b", true, "7|Renomeia o arquivo do B", "A", "B2");
        assertThat(seenOnDefault(project, "B2")).as("a branch report does not put it in the changelog").isEqualTo("never");
        assertThat(seenOnDefault(project, "A")).isNotEqualTo("never");
        assertThat(removed(project, "B")).as("a branch is not the default branch").isFalse();
        assertThat(jdbc.sql("""
                select cs.origin_mr_iid || '|' || cs.origin_mr_title || '|' || cs.origin_branch from changeset cs
                  join changelog cl on cl.id = cs.changelog_id join project p on p.id = cl.project_id
                 where p.slug = :p and cs.cs_id = 'B2'
                """).param("p", project).query(String.class).single()).isEqualTo("7|Renomeia o arquivo do B|chore/rename-b");

        // main reports again without B2: it was never there, so it is not "removed"
        report(token, project, connection, url, t0.plusSeconds(120), "main", true, "A", "B");
        assertThat(removed(project, "B2")).isFalse();
        assertThat(seenOnDefault(project, "B2")).isEqualTo("never");

        // the merge request is merged: main now has it and the origin is forgotten
        report(token, project, connection, url, t0.plusSeconds(180), "main", true, "A", "B2");
        assertThat(seenOnDefault(project, "B2")).isNotEqualTo("never");
        assertThat(jdbc.sql("""
                select cs.origin_branch from changeset cs join changelog cl on cl.id = cs.changelog_id
                  join project p on p.id = cl.project_id where p.slug = :p and cs.cs_id = 'B2'
                """).param("p", project).query(String.class).optional().orElse(null)).isNull();
        assertThat(removed(project, "B")).as("and now B is the one that is gone from main").isTrue();
    }

    @Test
    @DisplayName("a complete report from the default branch marks what is gone, and what comes back is no longer marked")
    void noticesRemovalAndReturn() {
        String workspace = unique("ws");
        String token = token(workspace);
        String project = unique("proj");
        String connection = unique("dev");
        String url = uniqueUrl();
        Instant t0 = Instant.parse("2026-09-15T10:00:00Z");

        report(token, project, connection, url, t0, "main", true, "A", "B", "C");
        assertThat(removed(project, "C")).isFalse();

        report(token, project, connection, url, t0.plusSeconds(60), "main", true, "A", "B");
        assertThat(removed(project, "C")).as("C is not in the changelog anymore").isTrue();
        assertThat(removed(project, "B")).isFalse();

        report(token, project, connection, url, t0.plusSeconds(120), "main", true, "A", "B", "C");
        assertThat(removed(project, "C")).as("it came back").isFalse();
    }

    @Test
    @DisplayName("a report that cannot be trusted as a photo of main changes nothing")
    void ignoresReportsThatAreNotAPhotoOfMain() {
        String workspace = unique("ws");
        String token = token(workspace);
        String project = unique("proj");
        String connection = unique("dev");
        String url = uniqueUrl();
        Instant t0 = Instant.parse("2026-09-15T10:00:00Z");
        report(token, project, connection, url, t0, "main", true, "A", "B", "C");

        report(token, project, connection, url, t0.plusSeconds(60), "chore/remove-b", true, "A", "C");
        assertThat(removed(project, "B")).as("a merge request's branch is not main").isFalse();

        report(token, project, connection, url, t0.plusSeconds(120), "main", false, "A");
        assertThat(removed(project, "B")).as("an incomplete list says nothing about what is missing").isFalse();
        assertThat(removed(project, "C")).isFalse();

        report(token, project, connection, url, t0.plusSeconds(180), "main", true, "A", "B", "C");
        report(token, project, connection, url, t0.plusSeconds(90), "main", true, "A");
        assertThat(removed(project, "B")).as("a report older than the last one used is stale").isFalse();
    }

    // ------------------------------------------------------------------ what it changes

    private String environmentId(String code) {
        for (JsonNode env : json(get(API + "/environments"))) {
            if (code.equals(env.get("code").asString())) {
                return env.get("id").asString();
            }
        }
        throw new AssertionError("seeded environment missing: " + code);
    }

    private String connection(String environment) {
        return json(post(API + "/connections", """
                {"environmentId":"%s","slug":"%s","displayName":"c","engine":"POSTGRESQL","jdbcUrl":"jdbc:postgresql://h/db"}
                """.formatted(environmentId(environment), unique("cx")))).get("id").asString();
    }

    private UUID insertChangeset(String changelog, String id, boolean removed) {
        return insertChangeset(changelog, id, removed, true);
    }

    private UUID insertChangeset(String changelog, String id, boolean removed, boolean inMain) {
        UUID uuid = UUID.randomUUID();
        jdbc.sql("insert into changeset (id, changelog_id, cs_id, author, file_path, has_rollback, removed_at, seen_on_default_at, origin_branch, origin_mr_iid, origin_mr_title) "
                        + "values (:id, :cl, :cs, 'ana', 'db/c.xml', true, " + (removed ? "now()" : "null") + ", "
                        + (inMain ? "now()" : "null") + ", " + (inMain ? "null, null, null" : "'feat/x', '9', 'Muda o x'") + ")")
                .param("id", uuid).param("cl", UUID.fromString(changelog)).param("cs", id).update();
        return uuid;
    }

    private void state(UUID changeset, String connection, String status) {
        jdbc.sql("insert into changeset_state (changeset_id, db_connection_id, status) values (:c, :d, :s)")
                .param("c", changeset).param("d", UUID.fromString(connection)).param("s", status).update();
    }

    @Test
    @DisplayName("the dialog and the matrix leave out what is removed, except while a database still holds it")
    void readsSkipTheRemoved() {
        String project = json(post(API + "/projects", "{\"slug\":\"%s\",\"name\":\"P\"}".formatted(unique("rm")))).get("id").asString();
        String changelog = json(post(API + "/projects/" + project + "/changelogs", "{\"name\":\"c\",\"path\":\"db/c.xml\",\"format\":\"XML\"}")).get("id").asString();
        String dev = connection("DEV");
        String hml = connection("HML");
        String pipeline = json(post(API + "/pipelines", "{\"name\":\"%s\",\"projectId\":\"%s\"}".formatted(unique("pl"), project))).get("id").asString();
        post(API + "/pipelines/" + pipeline + "/stages",
                "{\"sortOrder\":1,\"environmentId\":\"%s\",\"dbConnectionId\":\"%s\",\"requiresApproval\":false}".formatted(environmentId("DEV"), dev));
        String hmlStage = json(post(API + "/pipelines/" + pipeline + "/stages",
                "{\"sortOrder\":2,\"environmentId\":\"%s\",\"dbConnectionId\":\"%s\",\"requiresApproval\":false}".formatted(environmentId("HML"), hml))).get("id").asString();

        UUID kept = insertChangeset(changelog, "KEPT", false);
        state(kept, dev, "EXECUTED");
        UUID goneNeverRan = insertChangeset(changelog, "GONE-NEVER-RAN", true);
        state(goneNeverRan, dev, "ROLLED_BACK");
        UUID goneButHeld = insertChangeset(changelog, "GONE-BUT-HELD", true);
        state(goneButHeld, dev, "EXECUTED");
        UUID inAMergeRequest = insertChangeset(changelog, "ONLY-IN-AN-MR", false, false);
        state(inAMergeRequest, dev, "PENDING");

        JsonNode preview = json(get(API + "/pipelines/" + pipeline + "/stages/" + hmlStage + "/preview"));
        assertThat(preview.get("readyCount").asInt()).as("only KEPT is waiting for HML").isEqualTo(1);
        assertThat(preview.get("notInPreviousCount").asInt()).as("a changeset only in a merge request is not in the changelog yet").isZero();
        assertThat(preview.get("notInPreviousCount").asInt()).as("a removed file does not hold the deploy back").isZero();
        assertThat(preview.get("removedCount").asInt()).as("DEV still holds one of them").isEqualTo(1);
        assertThat(preview.get("removed").get(0).get("id").asString()).isEqualTo("GONE-BUT-HELD");

        JsonNode matrix = json(get(API + "/pipelines/" + pipeline + "/matrix"));
        List<String> rows = matrix.get("rows").valueStream().map(r -> r.get("changeset").asString()).toList();
        assertThat(rows).as("what no database holds is not shown, and neither is what only a merge request has").containsExactly("KEPT", "GONE-BUT-HELD");
        assertThat(matrix.get("kpis").get("total").asInt()).as("the count is of what is still in the changelog").isEqualTo(1);
        assertThat(matrix.get("kpis").get("awaitingPromotion").asInt()).isEqualTo(1);
        JsonNode held = matrix.get("rows").get(1);
        assertThat(held.get("removed").asBoolean()).isTrue();
        assertThat(held.get("cells").get(1).get("status").asString()).as("no next stop for a file that is gone").isEqualTo("NOT_REACHED");
    }
}
