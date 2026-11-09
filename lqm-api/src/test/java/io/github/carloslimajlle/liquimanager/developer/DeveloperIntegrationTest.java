package io.github.carloslimajlle.liquimanager.developer;

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
 * Developer Mode: the changelog detail and the repository files. SUP-1 ran in DEV and PRD, SUP-2 only in DEV,
 * SUP-3 failed in PRD; SUP-NR has no rollback.
 */
class DeveloperIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String workspace;
    private UUID workspaceId;
    private String project;
    private UUID projectId;
    private String changelogId;
    private String token;

    @BeforeEach
    void scenario() {
        GITLAB.files.clear();
        GITLAB.reads.clear();
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Dev\"}".formatted(workspace))).get("id").asString());
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}");
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"PRD\",\"name\":\"PRD\",\"sortOrder\":2,\"isProduction\":true}");
        project = unique("proj");
        token = tokens.issue(workspaceId, unique("ing"), List.of("ingest"), null, Instant.now().plusSeconds(3600), "it").token();

        String noRollback = changeset("SUP-NR", "EXECUTED").replace("\"hasRollback\": true", "\"hasRollback\": false");
        ingest("DEV", "update", "SUCCESS", Instant.now().minusSeconds(900), String.join(",",
                changeset("SUP-1", "EXECUTED"), changeset("SUP-2", "EXECUTED"), noRollback));
        ingest("PRD", "update", "FAILURE", Instant.now().minusSeconds(600), String.join(",",
                changeset("SUP-1", "EXECUTED"), changeset("SUP-3", "FAILED")));

        projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        jdbc.sql("update project set repo_url = 'https://gitlab.test/grp/proj.git', default_branch = 'main' where id = :id")
                .param("id", projectId).update();
        changelogId = json(callIn(workspace, "GET", API + "/projects/" + projectId + "/changelogs", null)).get(0).get("id").asString();
    }

    private void ingest(String env, String command, String status, Instant at, String changesets) {
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token,
                payloadAt(env, UUID.randomUUID(), project, unique(env.toLowerCase()), uniqueUrl(), command, status, at, changesets));
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    private JsonNode detail() {
        HttpResponse<String> response = callIn(workspace, "GET", API + "/projects/" + projectId + "/changelogs/" + changelogId, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private static JsonNode changesetOf(JsonNode detail, String id) {
        for (JsonNode cs : detail.get("changesets")) {
            if (id.equals(cs.get("id").asString())) {
                return cs;
            }
        }
        throw new AssertionError("changeset missing: " + id);
    }

    // ------------------------------------------------------------------- detail

    @Test
    @DisplayName("the detail counts the changesets and those with rollback, and names the latest release")
    void kpis() {
        JsonNode detail = detail();

        assertThat(detail.get("kpis").get("changesets").asInt()).isEqualTo(4);
        assertThat(detail.get("kpis").get("withRollback").asInt()).isEqualTo(3);
        assertThat(detail.get("kpis").get("release").asString()).isEqualTo("release-2.1.0");
        assertThat(detail.get("kpis").get("lastFlow")).as("no flow yet: the key is left out").isNull();
        assertThat(detail.get("project").get("slug").asString()).isEqualTo(project);
        assertThat(detail.get("branch").asString()).isEqualTo("main");
        assertThat(detail.get("sourceConfigured").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("each changeset carries its state in production: applied, failed, or still pending")
    void productionStatus() {
        JsonNode detail = detail();

        assertThat(changesetOf(detail, "SUP-1").get("production").asString()).isEqualTo("EXECUTED");
        assertThat(changesetOf(detail, "SUP-2").get("production").asString()).as("only ran in DEV").isEqualTo("PENDING");
        assertThat(changesetOf(detail, "SUP-3").get("production").asString()).isEqualTo("FAILED");
        assertThat(changesetOf(detail, "SUP-NR").get("hasRollback").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("the files are the master changelog and every file that holds a changeset")
    void files() {
        List<String> files = detail().get("files").valueStream().map(JsonNode::asString).toList();

        assertThat(files).contains("db/changelog/db.changelog-master.xml", "db/changelog/work/SUP-1.xml");
        assertThat(files).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("the last flow is the newest flow operation, with its stages")
    void lastFlow() {
        ingest("PRD", "flow", "SUCCESS", Instant.now().minusSeconds(60), "");

        JsonNode flow = detail().get("kpis").get("lastFlow");

        assertThat(flow.get("status").asString()).isEqualTo("SUCCESS");
        assertThat(flow.get("operationId").asString()).isNotBlank();
        assertThat(flow.get("stages").asInt()).isZero();
    }

    @Test
    @DisplayName("a changelog of another project is not found")
    void wrongProject() {
        HttpResponse<String> response = callIn(workspace, "GET",
                API + "/projects/" + UUID.randomUUID() + "/changelogs/" + changelogId, null);

        assertThat(response.statusCode()).isEqualTo(404);
    }

    // -------------------------------------------------------------------- files

    private HttpResponse<String> file(String path, String query) {
        return callIn(workspace, "GET", API + "/projects/" + projectId + "/changelogs/" + changelogId + "/files?path=" + path + query, null);
    }

    @Test
    @DisplayName("a file is read from GitLab at the default branch, with the read token and the project path")
    void readsFile() {
        GITLAB.files.put("db/changelog/db.changelog-master.xml", "<databaseChangeLog/>");

        HttpResponse<String> response = file("db/changelog/db.changelog-master.xml", "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("<databaseChangeLog/>");
        assertThat(GITLAB.reads.get(0)).contains("/api/v4/projects/grp/proj/repository/files/", "ref=main", "token=glpat-READ-SECRET");
    }

    @Test
    @DisplayName("a commit asks for that version of the file")
    void readsAtACommit() {
        GITLAB.files.put("db/changelog/db.changelog-master.xml", "old");

        file("db/changelog/db.changelog-master.xml", "&commit=9f3c2ab");

        assertThat(GITLAB.reads.get(0)).contains("ref=9f3c2ab");
    }

    @Test
    @DisplayName("a path Liquibase reports relative to the changelog is found under the changelog's folder")
    void findsRelativePath() {
        GITLAB.files.put("db/changelog/work/SUP-2.xml", "<changeSet id=\"SUP-2\"/>");

        HttpResponse<String> response = file("work/SUP-2.xml", "");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.body()).contains("SUP-2");
        assertThat(GITLAB.reads).as("the path as given was tried first").hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("a file GitLab does not have is a 404, and an unsafe path or ref is refused before GitLab is asked")
    void refusals() {
        assertThat(file("db/nothing.xml", "").statusCode()).isEqualTo(404);

        GITLAB.reads.clear();
        assertThat(file("../etc/passwd", "").statusCode()).isEqualTo(422);
        assertThat(file("%2Fetc%2Fpasswd", "").statusCode()).isEqualTo(422);
        assertThat(file("db/a.xml", "&commit=a%20b%26x").statusCode()).isEqualTo(422);
        assertThat(GITLAB.reads).isEmpty();
    }

    @Test
    @DisplayName("a project with no repository URL cannot show files")
    void noRepository() {
        jdbc.sql("update project set repo_url = null where id = :id").param("id", projectId).update();

        assertThat(file("db/a.xml", "").statusCode()).isEqualTo(422);
        assertThat(detail().get("sourceConfigured").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------- pure helpers

    @Test
    @DisplayName("the repository path comes out of the URL, whatever its form")
    void projectPaths() {
        assertThat(DeveloperController.projectPath("https://gitlab.x/group/sub/proj.git")).isEqualTo("group/sub/proj");
        assertThat(DeveloperController.projectPath("https://gitlab.x/group/proj/")).isEqualTo("group/proj");
    }

    @Test
    @DisplayName("candidates go from the path as given up through the folders above the master changelog")
    void candidates() {
        assertThat(DeveloperController.candidates("db/changelog/a.xml", "src/main/resources/db/changelog/master.xml"))
                .containsExactly("db/changelog/a.xml",
                        "src/main/resources/db/changelog/db/changelog/a.xml",
                        "src/main/resources/db/db/changelog/a.xml",
                        "src/main/resources/db/changelog/a.xml",
                        "src/main/db/changelog/a.xml",
                        "src/db/changelog/a.xml");
    }
}
