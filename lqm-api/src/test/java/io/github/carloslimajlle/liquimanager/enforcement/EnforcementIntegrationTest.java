package io.github.carloslimajlle.liquimanager.enforcement;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/**
 * Screen 06 over real ingestion. DEV was evaluated and only warned; PRD was evaluated and
 * blocked by a CRITICAL finding.
 */
class EnforcementIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String project;
    private String pipelineId;

    private static String withPolicy(String payload, String gate, String findings) {
        return payload.replace("\"error\":",
                "\"policy\": { \"gateResult\": \"" + gate + "\", \"findings\": [" + findings + "] }, \"error\":");
    }

    private static String finding(String check, int severity, String result, String changesetId) {
        return """
                { "check": "%s", "severity": %d, "result": "%s", "changeset": "%s", "message": "msg %s" }
                """.formatted(check, severity, result, changesetId, check);
    }

    @BeforeEach
    void scenario() {
        project = unique("proj");
        String token = serviceToken("ingest");
        String dev = unique("dev");
        String prd = unique("prd");
        Instant recent = Instant.now().minus(1, ChronoUnit.HOURS);

        ingest(token, withPolicy(payloadAt("DEV", UUID.randomUUID(), project, dev, uniqueUrl(), "update", "SUCCESS",
                recent, changeset("SUP-1-001", "EXECUTED")), "WARNED",
                finding("RollbackRequired", 2, "WARNED", "SUP-1-001")));
        ingest(token, withPolicy(payloadAt("PRD", UUID.randomUUID(), project, prd, uniqueUrl(), "update", "BLOCKED",
                recent.plusSeconds(60), changeset("SUP-1-002", "NOT_EXECUTED")), "BLOCKED",
                finding("DropColumnWarn", 3, "BLOCKED", "SUP-1-002") + ","
                        + finding("RollbackRequired", 2, "WARNED", "SUP-1-002")));

        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project)
                .query(UUID.class).single();
        pipelineId = json(post(API + "/pipelines", "{\"name\":\"" + unique("PIPE") + "\",\"projectId\":\"" + projectId + "\"}"))
                .get("id").asString();
        addStage(1, "DEV", dev);
        addStage(2, "PRD", prd);
    }

    private void ingest(String token, String payload) {
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token, payload);
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    private void addStage(int order, String env, String connectionKey) {
        String environmentId = null;
        for (JsonNode e : json(get(API + "/environments"))) {
            if (env.equals(e.get("code").asString())) {
                environmentId = e.get("id").asString();
            }
        }
        UUID connectionId = jdbc.sql("select id from db_connection where slug = :s").param("s", connectionKey)
                .query(UUID.class).single();
        HttpResponse<String> response = post(API + "/pipelines/" + pipelineId + "/stages", """
                { "sortOrder": %d, "environmentId": "%s", "dbConnectionId": "%s" }
                """.formatted(order, environmentId, connectionId));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private JsonNode enforcement(String query) {
        HttpResponse<String> response = get(API + "/enforcement?pipeline=" + pipelineId + query);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    @Test
    @DisplayName("each stage shows the verdict of its last evaluation and the findings by severity")
    void gateCards() {
        JsonNode gates = enforcement("").get("gates");

        assertThat(gates).hasSize(2);
        assertThat(gates.get(0).get("env").asString()).isEqualTo("DEV");
        assertThat(gates.get(0).get("result").asString()).isEqualTo("WARNED");
        assertThat(gates.get(0).get("bySeverity").get("MAJOR").asLong()).isEqualTo(1);
        assertThat(gates.get(1).get("result").asString()).isEqualTo("BLOCKED");
        assertThat(gates.get(1).get("bySeverity").get("CRITICAL").asLong()).isEqualTo(1);
        assertThat(gates.get(1).get("findings").asLong()).isEqualTo(2);
        assertThat(gates.get(1).get("production").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the counters count evaluations, violations and what was blocked")
    void kpis() {
        JsonNode kpis = enforcement("").get("kpis");

        assertThat(kpis.get("evaluations").asLong()).isEqualTo(2);
        assertThat(kpis.get("violations").asLong()).as("severity 2 or more").isEqualTo(3);
        assertThat(kpis.get("blocked").asLong()).isEqualTo(1);
        assertThat(kpis.get("passRate").asDouble()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("findings come most severe first, each with its changeset, database and environment")
    void findings() {
        JsonNode body = enforcement("");
        JsonNode findings = body.get("findings");

        assertThat(body.get("totalFindings").asLong()).isEqualTo(3);
        assertThat(findings.get(0).get("severity").asInt()).isEqualTo(3);
        assertThat(findings.get(0).get("check").asString()).isEqualTo("DropColumnWarn");
        assertThat(findings.get(0).get("changeset").asString()).isEqualTo("SUP-1-002");
        assertThat(findings.get(0).get("env").asString()).isEqualTo("PRD");
        assertThat(findings.get(0).get("operationId").asString()).isNotBlank();
        assertThat(findings.get(2).get("severity").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("the side card names the newest finding that blocked a deploy")
    void latestBlocked() {
        JsonNode blocked = enforcement("").get("latestBlocked");

        assertThat(blocked.get("check").asString()).isEqualTo("DropColumnWarn");
        assertThat(blocked.get("changeset").asString()).isEqualTo("SUP-1-002");
    }

    @Test
    @DisplayName("break-fix changesets applied in the period are listed with their environment")
    void breakFix() {
        String token = serviceToken("ingest");
        String conn = jdbc.sql("select slug from db_connection where id = (select db_connection_id from pipeline_stage where pipeline_id = :p and sort_order = 2)")
                .param("p", UUID.fromString(pipelineId)).query(String.class).single();
        String fix = changeset("SUP-HOTFIX", "EXECUTED").replace("\"labels\": \"release-2.1.0\"", "\"labels\": \"break-fix\"");
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token,
                payloadAt("PRD", UUID.randomUUID(), project, conn, uniqueUrl(), "update", "SUCCESS",
                        Instant.now().minus(30, ChronoUnit.MINUTES), fix));
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);

        JsonNode breakFix = enforcement("").get("breakFix");

        assertThat(breakFix.valueStream().map(b -> b.get("changeset").asString()).toList()).contains("SUP-HOTFIX");
    }

    @Test
    @DisplayName("a pipeline with no stages is empty, not an error, and has no pass rate to invent")
    void emptyPipeline() {
        String empty = json(post(API + "/pipelines", "{\"name\":\"" + unique("EMPTY") + "\"}")).get("id").asString();

        JsonNode body = json(get(API + "/enforcement?pipeline=" + empty));

        assertThat(body.get("gates")).isEmpty();
        assertThat(body.get("findings")).isEmpty();
        assertThat(body.get("kpis").get("passRate")).as("omitted when null").isNull();
    }

    @Test
    @DisplayName("unknown pipeline is 404 and a bad period is 422")
    void errors() {
        assertThat(get(API + "/enforcement?pipeline=" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(get(API + "/enforcement?pipeline=" + pipelineId + "&period=3d").statusCode()).isEqualTo(422);
    }
}
