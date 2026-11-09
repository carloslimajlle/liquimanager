package io.github.carloslimajlle.liquimanager.policies;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** What is in a later environment has to be in the one before it: the answer the deploy job asks for before it applies anything. */
class PromotionGateIntegrationTest extends AbstractDatabaseTest {

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

    private JsonNode gate(String token, String project, String env) {
        return json(call("GET", API + "/policies/promotion-gate?project=" + project + "&env=" + env, token, null));
    }

    @Test
    @DisplayName("the first stage is held by nothing; a later stage is held by the one before it, with what that one has executed")
    void gate() {
        String slug = unique("gate");
        String project = json(post(API + "/projects", "{\"slug\":\"%s\",\"name\":\"G\"}".formatted(slug))).get("id").asString();
        String changelog = json(post(API + "/projects/" + project + "/changelogs",
                "{\"name\":\"c\",\"path\":\"db/c.xml\",\"format\":\"XML\"}")).get("id").asString();
        String dev = connection("DEV");
        String hml = connection("HML");
        String pipeline = json(post(API + "/pipelines", "{\"name\":\"%s\",\"projectId\":\"%s\"}".formatted(unique("pl"), project))).get("id").asString();
        post(API + "/pipelines/" + pipeline + "/stages", """
                {"sortOrder":1,"environmentId":"%s","dbConnectionId":"%s","requiresApproval":false}
                """.formatted(environmentId("DEV"), dev));
        post(API + "/pipelines/" + pipeline + "/stages", """
                {"sortOrder":2,"environmentId":"%s","dbConnectionId":"%s","requiresApproval":false}
                """.formatted(environmentId("HML"), hml));

        String token = serviceToken("checks:read");
        assertThat(gate(token, slug, "DEV").get("gated").asBoolean()).as("DEV is the first stage").isFalse();
        assertThat(gate(token, slug, "PRD").get("gated").asBoolean()).as("not a stage of this pipeline").isFalse();
        assertThat(gate(token, "no-such-project", "HML").get("gated").asBoolean()).isFalse();

        JsonNode empty = gate(token, slug, "HML");
        assertThat(empty.get("gated").asBoolean()).isTrue();
        assertThat(empty.get("previousEnv").asString()).isEqualTo("DEV");
        assertThat(empty.get("executed")).as("nothing reported from DEV yet").isEmpty();

        UUID changeset = UUID.randomUUID();
        jdbc.sql("insert into changeset (id, changelog_id, cs_id, author, file_path, has_rollback) values (:id, :cl, 'SUP-1', 'ana', 'db/c.xml', true)")
                .param("id", changeset).param("cl", UUID.fromString(changelog)).update();
        UUID other = UUID.randomUUID();
        jdbc.sql("insert into changeset (id, changelog_id, cs_id, author, file_path, has_rollback) values (:id, :cl, 'SUP-2', 'ana', 'db/c.xml', true)")
                .param("id", other).param("cl", UUID.fromString(changelog)).update();
        state(changeset, dev, "EXECUTED");
        state(other, dev, "PENDING");
        state(other, hml, "EXECUTED");

        JsonNode after = gate(token, slug, "hml");
        assertThat(after.get("executed")).as("only what DEV has executed, not what only HML has").hasSize(1);
        assertThat(after.get("executed").get(0).get("id").asString()).isEqualTo("SUP-1");
        assertThat(after.get("executed").get(0).get("author").asString()).isEqualTo("ana");
        assertThat(after.get("executed").get(0).get("filePath").asString()).isEqualTo("db/c.xml");
        assertThat(after.get("executed").get(0).has("checksum")).as("no checksum known for what DEV ran: left out, so nobody is accused").isFalse();

        // What DEV ran, in which version: the deploy compares it with the file it is about to apply.
        jdbc.sql("update changeset_state set applied_checksum = '9:devversion' where changeset_id = :c and db_connection_id = :d")
                .param("c", changeset).param("d", UUID.fromString(dev)).update();
        assertThat(gate(token, slug, "HML").get("executed").get(0).get("checksum").asString()).isEqualTo("9:devversion");
    }

    @Test
    @DisplayName("without a service token the answer is refused")
    void needsToken() {
        assertThat(call("GET", API + "/policies/promotion-gate?project=x&env=HML", null, null).statusCode()).isEqualTo(401);
    }

    private void state(UUID changeset, String connection, String status) {
        jdbc.sql("insert into changeset_state (changeset_id, db_connection_id, status) values (:c, :d, :s)")
                .param("c", changeset).param("d", UUID.fromString(connection)).param("s", status).update();
    }
}
