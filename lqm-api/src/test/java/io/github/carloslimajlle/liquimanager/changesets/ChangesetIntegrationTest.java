package io.github.carloslimajlle.liquimanager.changesets;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** Changesets as a thing to look at: those of a project with their state per environment, one with its whole story, and the links from the lists. */
class ChangesetIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private void ingest(String token, String project, String env, String connection, String url, String status, String changesets) {
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token,
                payloadAt(env, UUID.randomUUID(), project, connection, url, "update", status, Instant.now().minusSeconds(30), changesets));
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    @Test
    @DisplayName("a project lists its changesets with the status in each environment; one changeset tells where it ran; the lists link to both")
    void story() {
        String token = serviceToken("ingest");
        String project = unique("story");
        String dev = unique("dev");
        String prd = unique("prd");
        String devUrl = uniqueUrl();
        String prdUrl = uniqueUrl();
        ingest(token, project, "DEV", dev, devUrl, "SUCCESS", String.join(",", changeset("A-1", "EXECUTED"), changeset("A-2", "EXECUTED")));
        ingest(token, project, "PRD", prd, prdUrl, "FAILURE", changeset("A-1", "FAILED"));

        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        JsonNode list = json(get(API + "/projects/" + projectId + "/changesets"));

        assertThat(list.get("totalElements").asInt()).isEqualTo(2);
        assertThat(list.get("environments")).extracting(JsonNode::asString).contains("DEV", "PRD");
        JsonNode a1 = null;
        for (JsonNode row : list.get("content")) {
            if ("A-1".equals(row.get("changesetId").asString())) {
                a1 = row;
            }
        }
        assertThat(a1).isNotNull();
        assertThat(a1.get("environments")).hasSize(2);
        assertThat(a1.get("environments").toString()).contains("\"FAILED\"").contains("\"EXECUTED\"");

        assertThat(json(get(API + "/projects/" + projectId + "/changesets?status=FAILED")).get("totalElements").asInt()).isEqualTo(1);
        assertThat(json(get(API + "/projects/" + projectId + "/changesets?q=a-2")).get("totalElements").asInt()).isEqualTo(1);
        assertThat(json(get(API + "/projects/" + projectId + "/changesets?author=nobody")).get("totalElements").asInt()).isZero();

        JsonNode summary = json(get(API + "/projects/" + projectId + "/changesets/summary"));
        for (JsonNode env : summary) {
            if ("DEV".equals(env.get("environment").asString())) {
                assertThat(env.get("executed").asInt()).isEqualTo(2);
            }
            if ("PRD".equals(env.get("environment").asString())) {
                assertThat(env.get("failed").asInt()).isEqualTo(1);
            }
        }
        assertThat(summary.toString()).contains("\"DEV\"").contains("\"PRD\"");

        JsonNode one = json(get(API + "/changesets/" + a1.get("id").asString()));
        assertThat(one.get("project").asString()).isEqualTo(project);
        assertThat(one.get("states")).hasSize(2);
        assertThat(one.get("operations")).hasSize(2);
        assertThat(one.get("checksum").asString()).isNotBlank();

        assertThat(get(API + "/changesets/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(get(API + "/projects/" + UUID.randomUUID() + "/changesets").statusCode()).isEqualTo(404);

        JsonNode operations = json(get(API + "/operations?project=" + project));
        JsonNode first = operations.get("content").get(0);
        assertThat(first.get("projectId").asString()).isEqualTo(projectId.toString());
        assertThat(first.get("changesetId").asString()).isNotBlank();
        assertThat(first.get("changelogId").asString()).isNotBlank();

        JsonNode activity = json(get(API + "/dashboard/activity?project=" + projectId));
        assertThat(activity).hasSize(2);
        assertThat(activity.get(0).get("project").asString()).isEqualTo(project);
        assertThat(activity.get(0).get("changesetId").asString()).isNotBlank();
        assertThat(json(get(API + "/dashboard/activity?project=" + UUID.randomUUID()))).isEmpty();
    }

    @Test
    @DisplayName("the source of a changeset is read from the repository with its block marked; why it could not be read is said, not thrown")
    void source() {
        String token = serviceToken("ingest");
        String project = unique("src");
        String dev = unique("dev");
        ingest(token, project, "DEV", dev, uniqueUrl(), "SUCCESS", String.join(",", changeset("A-1", "EXECUTED"), changeset("A-2", "EXECUTED")));
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        UUID a1 = jdbc.sql("select cs.id from changeset cs join changelog cl on cl.id = cs.changelog_id where cl.project_id = :p and cs.cs_id = 'A-1'")
                .param("p", projectId).query(UUID.class).single();

        assertThat(json(get(API + "/changesets/" + a1 + "/source")).get("reason").asString()).isEqualTo("NO_REPOSITORY");

        jdbc.sql("update project set repo_url = 'https://gitlab.test/grp/proj.git', default_branch = 'main' where id = :id")
                .param("id", projectId).update();
        GITLAB.files.clear();
        JsonNode missing = json(get(API + "/changesets/" + a1 + "/source"));
        assertThat(missing.get("found").asBoolean()).isFalse();
        assertThat(missing.get("reason").asString()).isEqualTo("NOT_FOUND");
        assertThat(missing.get("tried")).isNotEmpty();

        GITLAB.files.put("db/changelog/work/A-1.xml", String.join("\n",
                "<databaseChangeLog>",
                "  <changeSet id=\"A-0\" author=\"p.ricardo\"/>",
                "  <changeSet id=\"A-1\" author=\"p.ricardo\">",
                "    <createTable tableName=\"cliente\"/>",
                "  </changeSet>",
                "</databaseChangeLog>"));
        JsonNode read = json(get(API + "/changesets/" + a1 + "/source"));
        assertThat(read.get("found").asBoolean()).isTrue();
        assertThat(read.get("ref").asString()).isEqualTo("main");
        assertThat(read.get("refKind").asString()).isEqualTo("BRANCH");
        assertThat(read.get("format").asString()).isEqualTo("XML");
        assertThat(read.get("startLine").asInt()).isEqualTo(3);
        assertThat(read.get("endLine").asInt()).isEqualTo(5);
        assertThat(read.get("content").asString()).contains("createTable");

        assertThat(get(API + "/changesets/" + a1 + "/source?ref=a%20b").statusCode()).isEqualTo(422);
        assertThat(get(API + "/changesets/" + UUID.randomUUID() + "/source").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("a promotion lists what approving it would apply: executed in the stage before, not yet in its own")
    void promotionChanges() {
        String token = serviceToken("ingest");
        String project = unique("promo");
        String dev = unique("dev");
        String hml = unique("hml");
        ingest(token, project, "DEV", dev, uniqueUrl(), "SUCCESS", String.join(",", changeset("P-1", "EXECUTED"), changeset("P-2", "EXECUTED")));
        ingest(token, project, "HML", hml, uniqueUrl(), "SUCCESS", changeset("P-1", "EXECUTED"));
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        String pipeline = json(post(API + "/pipelines", "{\"name\":\"%s\",\"projectId\":\"%s\"}".formatted(unique("PL"), projectId))).get("id").asString();
        String hmlStage = null;
        int order = 1;
        for (String[] target : new String[][] {{"DEV", dev}, {"HML", hml}}) {
            UUID connection = jdbc.sql("select id from db_connection where slug = :s").param("s", target[1]).query(UUID.class).single();
            hmlStage = json(post(API + "/pipelines/" + pipeline + "/stages", "{\"sortOrder\":%d,\"environmentId\":\"%s\",\"dbConnectionId\":\"%s\",\"requiresApproval\":true}"
                    .formatted(order++, environmentId(target[0]), connection))).get("id").asString();
        }
        UUID promotion = UUID.randomUUID();
        jdbc.sql("""
                insert into promotion (id, workspace_id, pipeline_id, stage_id, environment, status, requested_by)
                select :id, workspace_id, :pl, :st, 'HML', 'REQUESTED', 'ana' from project where id = :project
                """).param("id", promotion).param("pl", UUID.fromString(pipeline)).param("st", UUID.fromString(hmlStage)).param("project", projectId).update();

        JsonNode changes = json(get(API + "/promotions/" + promotion + "/changes"));

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).get("changesetId").asString()).isEqualTo("P-2");
        assertThat(changes.get(0).get("id").asString()).isNotBlank();
        assertThat(get(API + "/promotions/" + UUID.randomUUID() + "/changes").statusCode()).isEqualTo(404);
    }

    private String environmentId(String code) {
        for (JsonNode env : json(get(API + "/environments"))) {
            if (code.equals(env.get("code").asString())) {
                return env.get("id").asString();
            }
        }
        throw new AssertionError("seeded environment missing: " + code);
    }
}
