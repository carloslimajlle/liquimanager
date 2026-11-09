package io.github.carloslimajlle.liquimanager.matrix;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** What promoting to a stage would meet, before anybody clicks: ready, edited after the stage before ran it, and not run in the stage before. */
class PromotionPreviewIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

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

    private UUID changeset(String changelog, String id, String checksum) {
        UUID uuid = UUID.randomUUID();
        jdbc.sql("insert into changeset (id, changelog_id, cs_id, author, file_path, has_rollback, checksum) values (:id, :cl, :cs, 'ana', 'db/c.xml', true, :sum)")
                .param("id", uuid).param("cl", UUID.fromString(changelog)).param("cs", id).param("sum", checksum).update();
        return uuid;
    }

    private void state(UUID changeset, String connection, String status, String applied) {
        jdbc.sql("insert into changeset_state (changeset_id, db_connection_id, status, applied_checksum) values (:c, :d, :s, :a)")
                .param("c", changeset).param("d", UUID.fromString(connection)).param("s", status).param("a", applied).update();
    }

    @Test
    @DisplayName("it separates what is ready, what DEV ran in another version and what DEV never ran; the first stage has nothing before it")
    void preview() {
        String slug = unique("pv");
        String project = json(post(API + "/projects", "{\"slug\":\"%s\",\"name\":\"P\"}".formatted(slug))).get("id").asString();
        String changelog = json(post(API + "/projects/" + project + "/changelogs", "{\"name\":\"c\",\"path\":\"db/c.xml\",\"format\":\"XML\"}")).get("id").asString();
        String dev = connection("DEV");
        String hml = connection("HML");
        String pipeline = json(post(API + "/pipelines", "{\"name\":\"%s\",\"projectId\":\"%s\"}".formatted(unique("pl"), project))).get("id").asString();
        String devStage = json(post(API + "/pipelines/" + pipeline + "/stages",
                "{\"sortOrder\":1,\"environmentId\":\"%s\",\"dbConnectionId\":\"%s\",\"requiresApproval\":false}".formatted(environmentId("DEV"), dev))).get("id").asString();
        String hmlStage = json(post(API + "/pipelines/" + pipeline + "/stages",
                "{\"sortOrder\":2,\"environmentId\":\"%s\",\"dbConnectionId\":\"%s\",\"requiresApproval\":false}".formatted(environmentId("HML"), hml))).get("id").asString();

        UUID same = changeset(changelog, "SAME", "9:aaa");
        state(same, dev, "EXECUTED", "9:aaa");
        UUID edited = changeset(changelog, "EDITED", "9:new");
        state(edited, dev, "EXECUTED", "9:old");
        UUID done = changeset(changelog, "DONE", "9:ddd");
        state(done, dev, "EXECUTED", "9:ddd");
        state(done, hml, "EXECUTED", "9:ddd");
        UUID never = changeset(changelog, "NEVER-IN-DEV", "9:nnn");
        state(never, dev, "PENDING", null);
        UUID elsewhere = changeset(changelog, "NOT-FOR-HML", "9:eee");
        state(elsewhere, hml, "NOT_TARGETED", null);

        JsonNode first = json(get(API + "/pipelines/" + pipeline + "/stages/" + devStage + "/preview"));
        assertThat(first.get("gated").asBoolean()).as("DEV is the first stage").isFalse();

        JsonNode preview = json(get(API + "/pipelines/" + pipeline + "/stages/" + hmlStage + "/preview"));
        assertThat(preview.get("gated").asBoolean()).isTrue();
        assertThat(preview.get("previousEnv").asString()).isEqualTo("DEV");
        assertThat(preview.get("readyCount").asInt()).as("SAME and EDITED ran in DEV and not in HML; DONE already ran in HML").isEqualTo(2);
        assertThat(preview.get("editedCount").asInt()).isEqualTo(1);
        assertThat(preview.get("edited").get(0).get("id").asString()).isEqualTo("EDITED");
        assertThat(preview.get("edited").get(0).get("appliedChecksum").asString()).isEqualTo("9:old");
        assertThat(preview.get("edited").get(0).get("currentChecksum").asString()).isEqualTo("9:new");
        assertThat(preview.get("notInPreviousCount").asInt()).as("only what is in the changelog and in neither stage, and is meant for both").isEqualTo(1);
        assertThat(preview.get("notInPrevious").get(0).get("id").asString()).isEqualTo("NEVER-IN-DEV");

        assertThat(get(API + "/pipelines/" + pipeline + "/stages/" + UUID.randomUUID() + "/preview").statusCode()).isEqualTo(404);
    }
}
