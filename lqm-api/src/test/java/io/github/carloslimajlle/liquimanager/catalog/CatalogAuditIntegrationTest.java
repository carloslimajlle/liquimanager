package io.github.carloslimajlle.liquimanager.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** Every write to the catalogue leaves an audit event (docs/telas/10, docs/09 § 6), and none of them carries a secret. */
class CatalogAuditIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private HttpResponse<String> put(String path, String body) {
        return call("PUT", path, null, body);
    }

    private HttpResponse<String> delete(String path) {
        return call("DELETE", path, null, null);
    }

    private List<String> actions(String targetId) {
        return jdbc.sql("select action from audit_event where target_id = :id order by seq")
                .param("id", targetId).query(String.class).list();
    }

    private String environmentId(String code) {
        for (JsonNode env : json(get(API + "/environments"))) {
            if (code.equals(env.get("code").asString())) {
                return env.get("id").asString();
            }
        }
        throw new AssertionError("seeded environment missing: " + code);
    }

    @Test
    @DisplayName("creating, renaming and archiving a project, and a team, each leave an event with before and after")
    void projectAndTeam() {
        String teamId = json(post(API + "/teams", "{\"name\":\"" + unique("team") + "\"}")).get("id").asString();
        assertThat(put(API + "/teams/" + teamId, "{\"name\":\"renamed\"}").statusCode()).isEqualTo(200);
        assertThat(delete(API + "/teams/" + teamId).statusCode()).isEqualTo(204);
        assertThat(actions(teamId)).containsExactly("team.create", "team.update", "team.delete");

        String slug = unique("aud");
        String id = json(post(API + "/projects", "{\"slug\":\"%s\",\"name\":\"P\"}".formatted(slug))).get("id").asString();
        assertThat(put(API + "/projects/" + id, "{\"slug\":\"%s\",\"name\":\"Renamed\"}".formatted(slug)).statusCode()).isEqualTo(200);
        assertThat(delete(API + "/projects/" + id).statusCode()).isEqualTo(204);
        assertThat(actions(id)).containsExactly("project.create", "project.update", "project.archive");

        String name = jdbc.sql("select after ->> 'name' from audit_event where target_id = :id and action = 'project.update'")
                .param("id", id).query(String.class).single();
        String was = jdbc.sql("select before ->> 'name' from audit_event where target_id = :id and action = 'project.update'")
                .param("id", id).query(String.class).single();
        assertThat(was).isEqualTo("P");
        assertThat(name).isEqualTo("Renamed");
    }

    @Test
    @DisplayName("a changelog, an environment, a pipeline and its stage are audited")
    void changelogEnvironmentPipeline() {
        String projectId = json(post(API + "/projects", "{\"slug\":\"%s\",\"name\":\"P\"}".formatted(unique("aud")))).get("id").asString();

        String changelogId = json(post(API + "/projects/" + projectId + "/changelogs",
                "{\"name\":\"c\",\"path\":\"db/c.xml\",\"format\":\"XML\"}")).get("id").asString();
        assertThat(put(API + "/projects/" + projectId + "/changelogs/" + changelogId,
                "{\"name\":\"c2\",\"path\":\"db/c.xml\",\"format\":\"XML\"}").statusCode()).isEqualTo(200);
        assertThat(actions(changelogId)).containsExactly("changelog.create", "changelog.update");

        String code = ("A" + (int) (Math.random() * 90000 + 10000));
        String envId = json(post(API + "/environments", "{\"code\":\"%s\",\"name\":\"E\",\"sortOrder\":90}".formatted(code))).get("id").asString();
        assertThat(put(API + "/environments/" + envId, "{\"name\":\"E2\",\"sortOrder\":91}").statusCode()).isEqualTo(200);
        assertThat(actions(envId)).containsExactly("environment.create", "environment.update");

        String pipelineId = json(post(API + "/pipelines", "{\"name\":\"%s\",\"projectId\":\"%s\"}".formatted(unique("pl"), projectId))).get("id").asString();
        String connectionId = json(post(API + "/connections", """
                {"environmentId":"%s","slug":"%s","displayName":"c","engine":"POSTGRESQL","jdbcUrl":"jdbc:postgresql://h/db"}
                """.formatted(environmentId("DEV"), unique("cx")))).get("id").asString();
        String stageId = json(post(API + "/pipelines/" + pipelineId + "/stages", """
                {"sortOrder":1,"environmentId":"%s","dbConnectionId":"%s","requiresApproval":false}
                """.formatted(environmentId("DEV"), connectionId))).get("id").asString();
        assertThat(put(API + "/pipelines/" + pipelineId + "/stages/" + stageId, """
                {"sortOrder":2,"environmentId":"%s","dbConnectionId":"%s","requiresApproval":true}
                """.formatted(environmentId("DEV"), connectionId)).statusCode()).isEqualTo(200);
        assertThat(delete(API + "/pipelines/" + pipelineId + "/stages/" + stageId).statusCode()).isEqualTo(204);
        assertThat(actions(stageId)).containsExactly("pipeline-stage.create", "pipeline-stage.update", "pipeline-stage.delete");

        assertThat(put(API + "/pipelines/" + pipelineId, "{\"name\":\"renamed\",\"projectId\":\"%s\"}".formatted(projectId)).statusCode()).isEqualTo(200);
        assertThat(delete(API + "/pipelines/" + pipelineId).statusCode()).isEqualTo(204);
        assertThat(actions(pipelineId)).containsExactly("pipeline.create", "pipeline.update", "pipeline.delete");
    }

    @Test
    @DisplayName("a connection is audited on create, update, test and archive, and the trail has no password")
    void connection() {
        String id = json(post(API + "/connections", """
                {"environmentId":"%s","slug":"%s","displayName":"c","engine":"POSTGRESQL",
                 "jdbcUrl":"jdbc:postgresql://nowhere.invalid:5432/db","secretProvider":"ENV","secretRef":"lqm_never_set"}
                """.formatted(environmentId("DEV"), unique("cx")))).get("id").asString();
        assertThat(put(API + "/connections/" + id, """
                {"environmentId":"%s","slug":"%s","displayName":"c2","engine":"POSTGRESQL","jdbcUrl":"jdbc:postgresql://nowhere.invalid:5432/db"}
                """.formatted(environmentId("DEV"), unique("cx"))).statusCode()).isEqualTo(200);
        call("POST", API + "/connections/" + id + "/test", null, null);
        assertThat(delete(API + "/connections/" + id).statusCode()).isEqualTo(204);

        assertThat(actions(id)).containsExactly("connection.create", "connection.update", "connection.test", "connection.archive");
        String tested = jdbc.sql("select after::text from audit_event where target_id = :id and action = 'connection.test'")
                .param("id", id).query(String.class).single();
        assertThat(tested).contains("latencyMs").doesNotContain("message").doesNotContain("nowhere");
    }

    @Test
    @DisplayName("a service token is audited on create and revoke, and the token value never reaches the trail")
    void token() {
        HttpResponse<String> created = post(API + "/service-tokens", """
                {"name":"%s","scopes":["ingest"],"expiresAt":"%s"}
                """.formatted(unique("tok"), java.time.Instant.now().plusSeconds(86400)));
        assertThat(created.statusCode()).isEqualTo(201);
        String id = json(created).get("id").asString();
        String value = json(created).get("token").asString();
        assertThat(delete(API + "/service-tokens/" + id).statusCode()).isEqualTo(204);

        assertThat(actions(id)).containsExactly("token.create", "token.revoke");
        String trail = jdbc.sql("select coalesce(before::text, '') || coalesce(after::text, '') from audit_event where target_id = :id")
                .param("id", id).query(String.class).list().toString();
        assertThat(trail).doesNotContain(value);
    }
}
