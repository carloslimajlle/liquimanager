package io.github.carloslimajlle.liquimanager.matrix;

import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadIn;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/**
 * The Deployment Matrix over data created through real ingestion. One project, one
 * pipeline of three stages (DEV, TST, PRD), five changesets:
 *
 * <pre>
 *   SUP-1-001  ran everywhere                       -> in sync
 *   SUP-1-002  ran in DEV and TST                   -> pending in PRD
 *   SUP-1-003  ran in DEV, filtered out of TST      -> not targeted in TST, pending in PRD
 *   SUP-1-004  ran in DEV and TST, FAILED in PRD    -> failed
 *   SUP-1-005  break-fix, ran only in PRD           -> the delivered fix for 004
 * </pre>
 */
class MatrixIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String project;
    private String pipelineId;

    private static String changeset(String id, String outcome, String labels) {
        return """
                { "id": "%s", "author": "p.ricardo", "filePath": "db/changelog/work/%s.xml", "checksum": "9:abc",
                  "labels": "%s", "contexts": "dev,tst,prd", "hasRollback": true, "outcome": "%s", "order": 1 }
                """.formatted(id, id, labels, outcome);
    }

    private static String changeset(String id, String outcome) {
        return changeset(id, outcome, "release-2.1.0");
    }

    @BeforeEach
    void scenario() {
        project = unique("proj");
        String token = serviceToken("ingest");
        String dev = unique("dev");
        String tst = unique("tst");
        String prd = unique("prd");
        String devUrl = uniqueUrl();
        String tstUrl = uniqueUrl();
        String prdUrl = uniqueUrl();

        ingest(token, "DEV", dev, devUrl, "SUCCESS", String.join(",",
                changeset("SUP-1-001", "EXECUTED"), changeset("SUP-1-002", "EXECUTED"),
                changeset("SUP-1-003", "EXECUTED"), changeset("SUP-1-004", "EXECUTED")));
        ingest(token, "TST", tst, tstUrl, "SUCCESS", String.join(",",
                changeset("SUP-1-001", "EXECUTED"), changeset("SUP-1-002", "EXECUTED"),
                changeset("SUP-1-003", "FILTERED"), changeset("SUP-1-004", "EXECUTED")));
        ingest(token, "PRD", prd, prdUrl, "FAILURE", String.join(",",
                changeset("SUP-1-001", "EXECUTED"), changeset("SUP-1-004", "FAILED")));
        // Later, in its own transaction, so it is "seen after" the failure it answers.
        ingest(token, "PRD", prd, prdUrl, "SUCCESS", changeset("SUP-1-005", "EXECUTED", "release-2.2.0,break-fix"));

        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project)
                .query(UUID.class).single();
        pipelineId = json(post(API + "/pipelines", "{\"name\":\"" + unique("PIPE") + "\",\"projectId\":\"" + projectId + "\"}"))
                .get("id").asString();
        addStage(1, "DEV", dev);
        addStage(2, "TST", tst);
        addStage(3, "PRD", prd);
    }

    private void ingest(String token, String env, String connection, String url, String status, String changesets) {
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token,
                payloadIn(env, UUID.randomUUID(), project, connection, url, status, changesets));
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

    private JsonNode matrix(String query) {
        HttpResponse<String> response = get(API + "/pipelines/" + pipelineId + "/matrix" + query);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private static String[] statuses(JsonNode row) {
        String[] out = new String[row.get("cells").size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = row.get("cells").get(i).get("status").asString();
        }
        return out;
    }

    private static JsonNode row(JsonNode matrix, String changeset) {
        for (JsonNode row : matrix.get("rows")) {
            if (changeset.equals(row.get("changeset").asString())) {
                return row;
            }
        }
        throw new AssertionError("row missing: " + changeset);
    }

    // ------------------------------------------------------------------- grid

    @Test
    @DisplayName("the stages come back in pipeline order, with environment and connection")
    void stagesInOrder() {
        JsonNode stages = matrix("").get("stages");

        assertThat(stages.size()).isEqualTo(3);
        assertThat(stages.get(0).get("env").asString()).isEqualTo("DEV");
        assertThat(stages.get(1).get("env").asString()).isEqualTo("TST");
        assertThat(stages.get(2).get("env").asString()).isEqualTo("PRD");
        assertThat(stages.get(0).get("connection").asString()).startsWith("dev-");
        assertThat(stages.get(0).get("envName").asString()).as("the column header shows the name")
                .isEqualTo("Desenvolvimento");
    }

    @Test
    @DisplayName("each changeset shows the right cell in each environment")
    void cells() {
        JsonNode matrix = matrix("");

        assertThat(statuses(row(matrix, "SUP-1-001"))).containsExactly("EXECUTED", "EXECUTED", "EXECUTED");
        assertThat(statuses(row(matrix, "SUP-1-002"))).as("next stop is PRD")
                .containsExactly("EXECUTED", "EXECUTED", "PENDING");
        assertThat(statuses(row(matrix, "SUP-1-003"))).as("filtered out of TST by its context")
                .containsExactly("EXECUTED", "NOT_TARGETED", "PENDING");
        assertThat(statuses(row(matrix, "SUP-1-004"))).containsExactly("EXECUTED", "EXECUTED", "FAILED");
    }

    @Test
    @DisplayName("rows follow the order the changesets were first seen, then their id")
    void rowOrder() {
        JsonNode rows = matrix("").get("rows");

        assertThat(rows.size()).isEqualTo(5);
        assertThat(rows.get(0).get("changeset").asString()).isEqualTo("SUP-1-001");
        assertThat(rows.get(3).get("changeset").asString()).isEqualTo("SUP-1-004");
        assertThat(rows.get(4).get("changeset").asString()).as("seen later, in another operation")
                .isEqualTo("SUP-1-005");
    }

    @Test
    @DisplayName("a cell that ran carries the operation to click through to; one that did not has none")
    void cellsLinkToOperations() {
        JsonNode failed = row(matrix(""), "SUP-1-004").get("cells");

        assertThat(failed.get(2).get("operationId").asString()).isNotBlank();
        assertThat(failed.get(0).get("deployedAt").asString()).isNotBlank();

        JsonNode pending = row(matrix(""), "SUP-1-002").get("cells").get(2);
        assertThat(pending.get("operationId")).as("never ran in PRD").isNull();
    }

    @Test
    @DisplayName("a break-fix changeset is flagged, with its release")
    void breakFixRow() {
        JsonNode fix = row(matrix(""), "SUP-1-005");

        assertThat(fix.get("isBreakFix").asBoolean()).isTrue();
        assertThat(fix.get("release").asString()).isEqualTo("release-2.2.0");
        assertThat(row(matrix(""), "SUP-1-001").get("isBreakFix").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------- KPIs

    @Test
    @DisplayName("KPIs: what is in sync, what is waiting, and what failed but was fixed")
    void kpis() {
        JsonNode kpis = matrix("").get("kpis");

        assertThat(kpis.get("total").asInt()).isEqualTo(5);
        assertThat(kpis.get("environments").asInt()).isEqualTo(3);
        assertThat(kpis.get("inSyncAll").asInt()).as("only SUP-1-001 is everywhere").isEqualTo(1);
        assertThat(kpis.get("awaitingPromotion").asInt()).as("002, 003 and the break-fix").isEqualTo(3);
        assertThat(kpis.get("awaitingPromotionEnvironment").asString()).as("furthest pending").isEqualTo("PRD");
        assertThat(kpis.get("failedWithFix").asInt()).as("004 failed in PRD, 005 ran there later").isEqualTo(1);
    }

    @Test
    @DisplayName("a failure with no later break-fix is not counted as fixed")
    void failureWithoutFix() {
        JsonNode kpis = matrix("?release=release-2.1.0").get("kpis");

        assertThat(kpis.get("total").asInt()).isEqualTo(4);
        assertThat(kpis.get("failedWithFix").asInt()).as("the fix is in another release, outside this filter").isZero();
    }

    // ---------------------------------------------------------------- filters

    @Test
    @DisplayName("filters by release, matching the whole label")
    void filterByRelease() {
        assertThat(matrix("?release=release-2.1.0").get("totalElements").asInt()).isEqualTo(4);
        assertThat(matrix("?release=release-2.2.0").get("totalElements").asInt()).isEqualTo(1);
        assertThat(matrix("?release=release-2").get("totalElements").asInt())
                .as("a prefix is not a release").isZero();
    }

    @Test
    @DisplayName("free-text search matches the changeset id, author or file")
    void search() {
        assertThat(matrix("?q=sup-1-003").get("totalElements").asInt()).isEqualTo(1);
        assertThat(matrix("?q=p.ricardo").get("totalElements").asInt()).isEqualTo(5);
        assertThat(matrix("?q=nothing-here").get("totalElements").asInt()).isZero();
    }

    @Test
    @DisplayName("paging slices the rows but the KPIs still cover the whole filtered set")
    void pagingKeepsTheKpis() {
        JsonNode page = matrix("?size=2&page=1");

        assertThat(page.get("rows").size()).isEqualTo(2);
        assertThat(page.get("rows").get(0).get("changeset").asString()).isEqualTo("SUP-1-003");
        assertThat(page.get("totalElements").asInt()).isEqualTo(5);
        assertThat(page.get("kpis").get("total").asInt()).isEqualTo(5);
    }

    // ------------------------------------------------------------------ edges

    @Test
    @DisplayName("an unknown pipeline is a 404 with LQM-001")
    void unknownPipeline() {
        HttpResponse<String> response = get(API + "/pipelines/" + UUID.randomUUID() + "/matrix");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("LQM-001");
    }

    @Test
    @DisplayName("a pipeline with no stages has an empty matrix, not an error")
    void noStages() {
        String empty = json(post(API + "/pipelines", "{\"name\":\"" + unique("EMPTY") + "\"}")).get("id").asString();

        JsonNode matrix = json(get(API + "/pipelines/" + empty + "/matrix"));

        assertThat(matrix.get("stages").size()).isZero();
        assertThat(matrix.get("rows").size()).isZero();
        assertThat(matrix.get("kpis").get("total").asInt()).isZero();
    }
}
