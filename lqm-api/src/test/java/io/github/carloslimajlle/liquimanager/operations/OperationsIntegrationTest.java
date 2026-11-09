package io.github.carloslimajlle.liquimanager.operations;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payload;
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
 * Operation queries (E6) over data created through the real ingestion endpoint.
 *
 * <p>Each test gets its own project, so assertions about "everything in this project"
 * stay exact even though the database is shared. The scenario, all on 2026-09-15:
 * <ul>
 *   <li>A - SUCCESS in PRD
 *   <li>B - FAILURE in PRD, whose failed changeset had passed in DEV earlier
 *   <li>C - FAILURE in DEV
 * </ul>
 */
class OperationsIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";
    private static final String WINDOW = "from=2026-09-15T00:00:00Z&to=2026-09-16T00:00:00Z";

    private String project;
    private UUID opA;
    private UUID opB;
    private UUID opC;

    @BeforeEach
    void ingestScenario() {
        project = unique("proj");
        String prd = unique("prd");
        String dev = unique("dev");
        String prdUrl = uniqueUrl();
        String devUrl = uniqueUrl();
        String token = serviceToken("ingest");
        opA = UUID.randomUUID();
        opB = UUID.randomUUID();
        opC = UUID.randomUUID();

        // The changeset that will fail in PRD first succeeds in DEV (a different operation).
        ingest(token, payloadIn("DEV", UUID.randomUUID(), project, dev, devUrl, "SUCCESS",
                changeset("SUP-9-001", "EXECUTED")));

        ingest(token, payloadIn("PRD", opA, project, prd, prdUrl, "SUCCESS", changeset("SUP-8-001", "EXECUTED")));

        // The FAILED one is listed second on purpose: the API must still put it first.
        String withFinding = payloadIn("PRD", opB, project, prd, prdUrl, "FAILURE",
                changeset("SUP-9-002", "NOT_EXECUTED", 1) + "," + changeset("SUP-9-001", "FAILED", 2))
                .replace("\"changesets\":", """
                        "policy": { "gateResult": "BLOCKED", "findings": [ { "check": "RollbackRequired",
                          "severity": 2, "result": "WARNED", "changeset": "SUP-9-001", "message": "no rollback" } ] },
                          "changesets":""");
        ingest(token, withFinding);

        ingest(token, payloadIn("DEV", opC, project, dev, devUrl, "FAILURE", changeset("SUP-7-001", "FAILED")));
    }

    private void ingest(String token, String body) {
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token, body);
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    private JsonNode list(String query) {
        HttpResponse<String> response = get(API + "/operations?project=" + project + "&" + query);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    // -------------------------------------------------------------------- list

    @Test
    @DisplayName("lists the operations of a project, newest first, with the shape of the table")
    void lists() {
        JsonNode page = list("");

        assertThat(page.get("totalElements").asInt()).isEqualTo(4);
        JsonNode first = page.get("content").get(0);
        assertThat(first.get("project").asString()).isEqualTo(project);
        assertThat(first.get("command").asString()).isEqualTo("update");
        assertThat(first.get("release").asString()).isEqualTo("release-2.1.0");
        assertThat(first.get("durationMs").asInt()).isEqualTo(998);
        assertThat(first.get("changelog").asString()).isEqualTo("db.changelog-master");
    }

    @Test
    @DisplayName("filters by status, environment and command, each narrowing the set")
    void filters() {
        assertThat(list("status=FAILURE").get("totalElements").asInt()).isEqualTo(2);
        assertThat(list("status=FAILURE&env=prd").get("totalElements").asInt())
                .as("environment is case-insensitive").isEqualTo(1);
        assertThat(list("env=DEV").get("totalElements").asInt()).isEqualTo(2);
        assertThat(list("command=rollback").get("totalElements").asInt()).isZero();
        assertThat(list("type=database-change").get("totalElements").asInt()).isEqualTo(4);
    }

    @Test
    @DisplayName("free-text search reaches changeset ids, not just the operation columns")
    void searchesChangesets() {
        assertThat(list("q=SUP-7-001").get("totalElements").asInt()).isEqualTo(1);
        assertThat(list("q=sup-9-002").get("totalElements").asInt()).as("case-insensitive").isEqualTo(1);
        assertThat(list("q=" + opA).get("totalElements").asInt()).as("by operation id").isEqualTo(1);
        assertThat(list("q=nothing-matches-this").get("totalElements").asInt()).isZero();
    }

    @Test
    @DisplayName("paginates server-side and reports the real total")
    void paginates() {
        JsonNode first = list("size=3&page=0");
        JsonNode second = list("size=3&page=1");

        assertThat(first.get("content").size()).isEqualTo(3);
        assertThat(second.get("content").size()).isEqualTo(1);
        assertThat(first.get("totalElements").asInt()).isEqualTo(4);
        assertThat(second.get("page").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown sort key falls back to the default instead of reaching the SQL")
    void sortIsAllowListed() {
        HttpResponse<String> hostile = get(API + "/operations?project=" + project
                + "&sort=started_at;drop%20table%20operation,asc");

        assertThat(hostile.statusCode()).isEqualTo(200);
        assertThat(list("sort=status,asc").get("content").get(0).get("status").asString())
                .as("FAILURE sorts before SUCCESS").isEqualTo("FAILURE");
    }

    @Test
    @DisplayName("a page size above the cap is clamped, not rejected")
    void sizeIsCapped() {
        assertThat(list("size=100000").get("size").asInt()).isEqualTo(200);
    }

    // -------------------------------------------------------------------- KPIs

    @Test
    @DisplayName("KPIs describe exactly the filtered set")
    void kpis() {
        JsonNode kpis = json(get(API + "/operations/kpis?project=" + project + "&" + WINDOW));

        assertThat(kpis.get("total").asInt()).isEqualTo(4);
        assertThat(kpis.get("succeeded").asInt()).isEqualTo(2);
        assertThat(kpis.get("successRate").asDouble()).as("2 of 4 finished").isEqualTo(50.0);
        assertThat(kpis.get("medianDurationMs").asInt()).isEqualTo(998);
        assertThat(kpis.get("caughtBeforeProd").asInt()).as("only the DEV failure").isEqualTo(1);
        assertThat(kpis.get("failuresNonProd").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("narrowing a filter narrows the KPIs with it")
    void kpisFollowFilters() {
        JsonNode kpis = json(get(API + "/operations/kpis?project=" + project + "&env=PRD&" + WINDOW));

        assertThat(kpis.get("total").asInt()).isEqualTo(2);
        assertThat(kpis.get("caughtBeforeProd").asInt()).as("PRD is production").isZero();
    }

    @Test
    @DisplayName("no operations in the previous window means no trend, not a division by zero")
    void trendNeedsAPreviousWindow() {
        JsonNode kpis = json(get(API + "/operations/kpis?project=" + project + "&" + WINDOW));

        assertThat(kpis.get("trendPct")).as("nothing before 2026-09-15 in this project").isNull();
    }

    @Test
    @DisplayName("with no lower bound the KPIs cover all time, with no trend (the 7-day default is the screen's)")
    void kpisWithoutWindowCoverEverything() {
        JsonNode kpis = json(get(API + "/operations/kpis?project=" + project));

        assertThat(kpis.get("total").asInt()).as("the data is from September, well over 7 days old").isEqualTo(4);
        assertThat(kpis.get("trendPct")).as("no window, nothing to compare with").isNull();
    }

    @Test
    @DisplayName("an empty set has no success rate or median, rather than zero")
    void emptyKpis() {
        JsonNode kpis = json(get(API + "/operations/kpis?project=" + project + "&command=rollback&" + WINDOW));

        assertThat(kpis.get("total").asInt()).isZero();
        assertThat(kpis.get("successRate")).isNull();
        assertThat(kpis.get("medianDurationMs")).isNull();
    }

    // ------------------------------------------------------------------ detail

    @Test
    @DisplayName("the detail of a failure carries what the screen needs to diagnose it")
    void detailOfAFailure() {
        JsonNode detail = json(get(API + "/operations/" + opB));

        assertThat(detail.get("status").asString()).isEqualTo("FAILURE");
        assertThat(detail.get("environment").asString()).isEqualTo("PRD");
        assertThat(detail.get("errorMessage").asString()).isEqualTo("boom");
        assertThat(detail.get("gateResult").asString()).isEqualTo("BLOCKED");
        assertThat(detail.get("hasLog").asBoolean()).isTrue();
        assertThat(detail.get("runtime").get("collector").asString()).isEqualTo("lqm-cli 1.0.0");
        assertThat(detail.get("summary").get("failed").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the failed changeset shows its state in every environment, in pipeline order")
    void failedChangesetAcrossEnvironments() {
        JsonNode failed = json(get(API + "/operations/" + opB)).get("failedChangeset");

        assertThat(failed.get("id").asString()).isEqualTo("SUP-9-001");
        JsonNode environments = failed.get("environments");
        assertThat(environments.get(0).get("environment").asString()).isEqualTo("DEV");
        assertThat(environments.get(0).get("status").asString()).isEqualTo("EXECUTED");
        assertThat(environments.get(1).get("environment").asString()).isEqualTo("PRD");
        assertThat(environments.get(1).get("status").asString())
                .as("passed in DEV, failed in PRD: the data, not the changeset").isEqualTo("FAILED");
    }

    @Test
    @DisplayName("policy findings of the operation come with their check and severity")
    void detailFindings() {
        JsonNode findings = json(get(API + "/operations/" + opB)).get("findings");

        assertThat(findings.size()).isEqualTo(1);
        assertThat(findings.get(0).get("check").asString()).isEqualTo("RollbackRequired");
        assertThat(findings.get(0).get("severity").asInt()).isEqualTo(2);
        assertThat(findings.get(0).get("changeset").asString()).isEqualTo("SUP-9-001");
    }

    @Test
    @DisplayName("a successful operation has no failed changeset")
    void successHasNoFailedChangeset() {
        assertThat(json(get(API + "/operations/" + opA)).get("failedChangeset")).isNull();
    }

    @Test
    @DisplayName("an unknown operation is a 404 with LQM-001")
    void unknownOperation() {
        HttpResponse<String> response = get(API + "/operations/" + UUID.randomUUID());

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("code").asString()).isEqualTo("LQM-001");
    }

    // -------------------------------------------------------------- changesets

    @Test
    @DisplayName("changesets list failures first even when they ran last")
    void changesetsFailuresFirst() {
        JsonNode page = json(get(API + "/operations/" + opB + "/changesets"));

        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
        assertThat(page.get("content").get(0).get("changesetId").asString()).isEqualTo("SUP-9-001");
        assertThat(page.get("content").get(0).get("outcome").asString()).isEqualTo("FAILED");
        assertThat(page.get("content").get(0).get("release").asString()).isEqualTo("release-2.1.0");
        assertThat(page.get("content").get(1).get("outcome").asString()).isEqualTo("NOT_EXECUTED");
    }

    // --------------------------------------------------------------- log / sql

    @Test
    @DisplayName("log and SQL come back as plain text from blob storage")
    void logAndSql() {
        HttpResponse<String> log = get(API + "/operations/" + opB + "/log");
        HttpResponse<String> sql = get(API + "/operations/" + opB + "/sql");

        assertThat(log.statusCode()).isEqualTo(200);
        assertThat(log.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        assertThat(log.body()).isEqualTo("first line\nsecond line");
        assertThat(sql.body()).isEqualTo("create index ix on t(a);");
    }

    @Test
    @DisplayName("an operation with no log answers 404 worded for the log, not for the operation")
    void missingLog() {
        UUID noBlobs = UUID.randomUUID();
        String body = payload(noBlobs, project, unique("c"), uniqueUrl(), "SUCCESS", "")
                .replace("\"log\": \"first line\\nsecond line\", \"sql\": \"create index ix on t(a);\"",
                        "\"log\": null");
        ingest(serviceToken("ingest"), body);

        HttpResponse<String> response = get(API + "/operations/" + noBlobs + "/log");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(json(response).get("detail").asString()).contains("log");
    }
}
