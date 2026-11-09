package io.github.carloslimajlle.liquimanager.matrix;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadIn;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** A deploy reported for a stage opens, by itself, the approval request of the next stage when that stage asked for it. */
class AutoPromotionRequestIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String environmentId(String code) {
        for (JsonNode env : json(get(API + "/environments"))) {
            if (code.equals(env.get("code").asString())) {
                return env.get("id").asString();
            }
        }
        throw new AssertionError("seeded environment missing: " + code);
    }

    private void ingest(String token, String project, String env, String connection, String url, String status, String changesets) {
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token,
                payloadIn(env, UUID.randomUUID(), project, connection, url, status, changesets));
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    private int requests(String pipeline, String system) {
        return jdbc.sql("select count(*) from promotion where pipeline_id = :p and status = 'REQUESTED' and requested_by = :by")
                .param("p", UUID.fromString(pipeline)).param("by", system).query(Integer.class).single();
    }

    private String scenario(boolean auto, String token, String project, String dev, String devUrl) {
        String hml = unique("hml");
        ingest(token, project, "DEV", dev, devUrl, "SUCCESS", changeset("SEED-1", "EXECUTED"));
        ingest(token, project, "HML", hml, uniqueUrl(), "SUCCESS", changeset("SEED-1", "EXECUTED"));
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        String pipeline = json(post(API + "/pipelines", "{\"name\":\"%s\",\"projectId\":\"%s\"}".formatted(unique("PL"), projectId)))
                .get("id").asString();
        stage(pipeline, 1, "DEV", dev, false, false);
        stage(pipeline, 2, "HML", hml, true, auto);
        return pipeline;
    }

    private void stage(String pipeline, int order, String env, String connectionKey, boolean approval, boolean auto) {
        UUID connection = jdbc.sql("select id from db_connection where slug = :s").param("s", connectionKey).query(UUID.class).single();
        HttpResponse<String> response = post(API + "/pipelines/" + pipeline + "/stages", """
                {"sortOrder":%d,"environmentId":"%s","dbConnectionId":"%s","requiresApproval":%s,"autoRequest":%s}
                """.formatted(order, environmentId(env), connection, approval, auto));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    @Test
    @DisplayName("a deploy in DEV opens one REQUESTED promotion for HML, in the name of system, once")
    void opensOne() {
        String token = serviceToken("ingest");
        String project = unique("auto");
        String dev = unique("dev");
        String url = uniqueUrl();
        String pipeline = scenario(true, token, project, dev, url);
        assertThat(requests(pipeline, "system")).as("nothing before the deploy").isZero();

        ingest(token, project, "DEV", dev, url, "SUCCESS", changeset("SUP-9", "EXECUTED"));
        assertThat(requests(pipeline, "system")).isEqualTo(1);

        ingest(token, project, "DEV", dev, url, "SUCCESS", changeset("SUP-10", "EXECUTED"));
        assertThat(requests(pipeline, "system")).as("an open request is not piled up").isEqualTo(1);
        assertThat(jdbc.sql("select count(*) from audit_event where action = 'pipeline.promote.request' and actor = 'system'")
                .query(Long.class).single()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("without the option, with nothing executed, or with a failed deploy, nothing is requested")
    void opensNothing() {
        String token = serviceToken("ingest");
        String project = unique("auto");
        String dev = unique("dev");
        String url = uniqueUrl();
        String off = scenario(false, token, project, dev, url);
        ingest(token, project, "DEV", dev, url, "SUCCESS", changeset("SUP-9", "EXECUTED"));
        assertThat(requests(off, "system")).as("option off").isZero();

        String project2 = unique("auto");
        String dev2 = unique("dev");
        String url2 = uniqueUrl();
        String on = scenario(true, token, project2, dev2, url2);
        ingest(token, project2, "DEV", dev2, url2, "SUCCESS", changeset("SUP-9", "NOT_EXECUTED"));
        ingest(token, project2, "DEV", dev2, url2, "FAILURE", changeset("SUP-9", "FAILED"));
        assertThat(requests(on, "system")).as("nothing executed, deploy failed").isZero();
    }
}
