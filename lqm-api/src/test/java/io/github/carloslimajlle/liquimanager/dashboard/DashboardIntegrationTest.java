package io.github.carloslimajlle.liquimanager.dashboard;

import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/**
 * Dashboard figures against an ISOLATED workspace, so the numbers can be exact: the
 * shared database holds data from every other test, but none of it is in this workspace.
 *
 * <p>Operations are placed relative to the real "now", because the service computes its
 * window from the clock. Days ago, current window = 7 days:
 *
 * <pre>
 *   -6  A  DEV  update SUCCESS   runs CX                      deploy
 *   -5  B  TST  update SUCCESS   runs CY                      deploy
 *   -4  D  PRD  update SUCCESS   runs CX                      deploy  (CX: DEV -6d -> PRD -4d = 48 h)
 *   -3  C  PRD  update FAILURE   fails CZ                     deploy, failed
 *   -2  R  PRD  rollback SUCCESS                              rollback
 *   -1  N  DEV  update SUCCESS   CX already applied           NOT a deploy
 *   -1  K  DEV  checks run                                    NOT a deploy
 *  previous window (7 to 14 days ago):
 *   -9, -10     two successful updates in DEV                 2 deploys
 * </pre>
 */
class DashboardIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";
    private static final Duration DAY = Duration.ofDays(1);

    private String workspace;
    private String token;
    private String project;
    private final Instant now = Instant.now();

    private static String changeset(String id, String outcome) {
        return """
                { "id": "%s", "author": "p.ricardo", "filePath": "db/changelog/work/%s.xml", "checksum": "9:abc",
                  "labels": "release-2.1.0", "contexts": "dev,tst,prd", "hasRollback": true,
                  "outcome": "%s", "order": 1 }
                """.formatted(id, id, outcome);
    }

    @BeforeEach
    void scenario() {
        workspace = unique("ws");
        HttpResponse<String> created = post(API + "/workspaces", "{\"slug\":\"%s\",\"name\":\"Isolated\"}".formatted(workspace));
        assertThat(created.statusCode()).isEqualTo(201);
        UUID workspaceId = UUID.fromString(json(created).get("id").asString());

        for (String[] env : new String[][]{{"DEV", "1", "false"}, {"TST", "2", "false"}, {"PRD", "3", "true"}}) {
            HttpResponse<String> response = callIn(workspace, "POST", API + "/environments", """
                    { "code": "%s", "name": "%s", "sortOrder": %s, "isProduction": %s }
                    """.formatted(env[0], env[0], env[1], env[2]));
            assertThat(response.statusCode()).isEqualTo(201);
        }

        token = tokens.issue(workspaceId, unique("dash"), java.util.List.of("ingest"), null,
                now.plusSeconds(3600), "integration-test").token();
        project = unique("proj");
        String dev = unique("dev");
        String tst = unique("tst");
        String prd = unique("prd");
        String devUrl = uniqueUrl();
        String tstUrl = uniqueUrl();
        String prdUrl = uniqueUrl();

        op("DEV", dev, devUrl, "update", "SUCCESS", now.minus(DAY.multipliedBy(6)), changeset("CX", "EXECUTED"));
        op("TST", tst, tstUrl, "update", "SUCCESS", now.minus(DAY.multipliedBy(5)), changeset("CY", "EXECUTED"));
        op("PRD", prd, prdUrl, "update", "SUCCESS", now.minus(DAY.multipliedBy(4)), changeset("CX", "EXECUTED"));
        op("PRD", prd, prdUrl, "update", "FAILURE", now.minus(DAY.multipliedBy(3)), changeset("CZ", "FAILED"));
        op("PRD", prd, prdUrl, "rollback", "SUCCESS", now.minus(DAY.multipliedBy(2)), "");
        op("DEV", dev, devUrl, "update", "SUCCESS", now.minus(DAY), changeset("CX", "PREVIOUSLY_RUN"));
        op("DEV", dev, devUrl, "checks run", "SUCCESS", now.minus(DAY).minus(Duration.ofHours(1)), "");

        op("DEV", dev, devUrl, "update", "SUCCESS", now.minus(DAY.multipliedBy(9)), changeset("CP1", "EXECUTED"));
        op("DEV", dev, devUrl, "update", "SUCCESS", now.minus(DAY.multipliedBy(10)), changeset("CP2", "EXECUTED"));
    }

    private void op(String env, String connection, String url, String command, String status, Instant at,
                    String changesets) {
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token,
                payloadAt(env, UUID.randomUUID(), project, connection, url, command, status, at, changesets));
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    private JsonNode dashboard(String query) {
        HttpResponse<String> response = callIn(workspace, "GET", API + "/dashboard" + query, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    // ------------------------------------------------------------------- DORA

    @Test
    @DisplayName("deploy frequency counts only real deploys, per day, with its trend against the previous window")
    void deployFrequency() {
        JsonNode frequency = dashboard("").get("dora").get("deployFrequency");

        assertThat(frequency.get("deploys").asInt()).as("A, B, D, C — not the rollback, N or the checks").isEqualTo(4);
        assertThat(frequency.get("perDay").asDouble()).isEqualTo(0.57);
        assertThat(frequency.get("trendPct").asDouble()).as("4 now against 2 before").isEqualTo(100.0);
    }

    @Test
    @DisplayName("change failure rate is failed deploys over deploys, and its trend is in points")
    void changeFailureRate() {
        JsonNode rate = dashboard("").get("dora").get("changeFailureRate");

        assertThat(rate.get("part").asInt()).isEqualTo(1);
        assertThat(rate.get("whole").asInt()).isEqualTo(4);
        assertThat(rate.get("value").asDouble()).isEqualTo(25.0);
        assertThat(rate.get("trendPts").asDouble()).as("0% before, 25% now").isEqualTo(25.0);
    }

    @Test
    @DisplayName("rollback rate is successful rollbacks over SUCCESSFUL deploys (the failed one is not in the base)")
    void rollbackRate() {
        JsonNode rate = dashboard("").get("dora").get("rollbackRate");

        assertThat(rate.get("part").asInt()).isEqualTo(1);
        assertThat(rate.get("whole").asInt()).as("A, B and D succeeded; C did not").isEqualTo(3);
        assertThat(rate.get("value").asDouble()).isEqualTo(33.3);
    }

    @Test
    @DisplayName("cycle time is the median from the first non-production run to production")
    void cycleTime() {
        JsonNode cycle = dashboard("").get("dora").get("cycleTime");

        assertThat(cycle.get("changesets").asInt()).as("only CX reached PRD successfully").isEqualTo(1);
        assertThat(cycle.get("hours").asDouble()).as("DEV six days ago, PRD four days ago").isEqualTo(48.0);
        assertThat(cycle.get("trendPct")).as("nothing reached PRD in the window before").isNull();
    }

    @Test
    @DisplayName("a widening period brings the older operations into the window")
    void widerPeriod() {
        JsonNode frequency = dashboard("?period=30d").get("dora").get("deployFrequency");

        assertThat(frequency.get("deploys").asInt()).as("the two from 9 and 10 days ago join in").isEqualTo(6);
        assertThat(frequency.get("perDay").asDouble()).isEqualTo(0.2);
    }

    @Test
    @DisplayName("an invalid period is a 422, not a silent fallback")
    void invalidPeriod() {
        HttpResponse<String> response = callIn(workspace, "GET", API + "/dashboard?period=1y", null);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(json(response).get("code").asString()).isEqualTo("LQM-422");
    }

    @Test
    @DisplayName("an empty workspace has no rates, rather than zeros dressed up as data")
    void emptyWorkspace() {
        String empty = unique("empty");
        post(API + "/workspaces", "{\"slug\":\"%s\",\"name\":\"Empty\"}".formatted(empty));

        JsonNode dora = json(callIn(empty, "GET", API + "/dashboard", null)).get("dora");

        assertThat(dora.get("deployFrequency").get("deploys").asInt()).isZero();
        assertThat(dora.get("changeFailureRate").get("value")).isNull();
        assertThat(dora.get("rollbackRate").get("value")).isNull();
        assertThat(dora.get("cycleTime").get("hours")).isNull();
    }

    // ----------------------------------------------------------------- header

    @Test
    @DisplayName("the header counts this workspace's teams, databases, pipelines and the period's operations")
    void header() {
        JsonNode header = dashboard("").get("header");

        assertThat(header.get("connections").asInt()).as("DEV, TST and PRD").isEqualTo(3);
        assertThat(header.get("teams").asInt()).isZero();
        assertThat(header.get("pipelines").asInt()).isZero();
        assertThat(header.get("operations").asInt()).as("seven in the last 7 days").isEqualTo(7);
    }

    // --------------------------------------------------------------- activity

    @Test
    @DisplayName("activity lists the newest operations first, with what each was about")
    void activity() {
        HttpResponse<String> response = callIn(workspace, "GET", API + "/dashboard/activity?limit=3", null);
        JsonNode rows = json(response);

        assertThat(rows.size()).isEqualTo(3);
        assertThat(rows.get(0).get("command").asString()).as("the checks run, an hour before N").isEqualTo("update");
        assertThat(rows.get(0).get("subject").asString()).isEqualTo("CX");
        assertThat(rows.get(0).get("environment").asString()).isEqualTo("DEV");
        assertThat(rows.get(1).get("command").asString()).isEqualTo("checks run");
        assertThat(rows.get(2).get("command").asString()).isEqualTo("rollback");
        assertThat(rows.get(2).get("subject")).as("a rollback with no changesets has no subject").isNull();
    }

    @Test
    @DisplayName("an operation of several changesets reads as a package of its release")
    void packageSubject() {
        op("DEV", unique("pk"), uniqueUrl(), "update", "SUCCESS", now.plusSeconds(60),
                changeset("P1", "EXECUTED") + "," + changeset("P2", "EXECUTED"));

        JsonNode top = json(callIn(workspace, "GET", API + "/dashboard/activity?limit=1", null)).get(0);

        assertThat(top.get("subject").asString()).isEqualTo("2.1.0 package");
        assertThat(top.get("scope").asInt()).isEqualTo(2);
    }
}
