package io.github.carloslimajlle.liquimanager.dashboard;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/**
 * The policy and drift bands of the dashboard, read from {@code metric_daily}, in an isolated workspace so the
 * numbers can be exact. The current window is the last 7 days; the previous one is the 7 before that.
 *
 * <pre>
 *   -3d  DEV  gate PASSED
 *   -3d  DEV  gate WARNED
 *   -2d  PRD  gate BLOCKED         (in production: counts as "blocked before production")
 *   -2d  DEV  gate BLOCKED         (not in production: a check, but not that KPI)
 *   -1d  PRD  gate PASSED
 *  previous window: -9d DEV gate PASSED, -9d PRD gate BLOCKED
 *  drift: 3 runs this window (1 with 2 added + 1 modified), 2 in the previous (1 with drift, 1 item)
 * </pre>
 */
class BandsIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";
    private static final Duration DAY = Duration.ofDays(1);

    @Autowired
    MetricsService metrics;

    @Autowired
    MetricsJobs jobs;

    private String workspace;
    private UUID workspaceId;
    private String token;
    private String project;
    private String devConnection;
    private String prdConnection;
    private UUID devConnectionId;
    private final Instant now = Instant.now();

    @BeforeEach
    void scenario() {
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Bands\"}".formatted(workspace))).get("id").asString());
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}");
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"PRD\",\"name\":\"PRD\",\"sortOrder\":2,\"isProduction\":true}");
        token = tokens.issue(workspaceId, unique("t"), List.of("ingest"), null, now.plusSeconds(3600), "integration-test").token();
        project = unique("proj");
        devConnection = unique("dev");
        prdConnection = unique("prd");

        gate("DEV", devConnection, now.minus(DAY.multipliedBy(3)), "PASSED");
        gate("DEV", devConnection, now.minus(DAY.multipliedBy(3)).plusSeconds(60), "WARNED");
        gate("PRD", prdConnection, now.minus(DAY.multipliedBy(2)), "BLOCKED");
        gate("DEV", devConnection, now.minus(DAY.multipliedBy(2)).plusSeconds(60), "BLOCKED");
        gate("PRD", prdConnection, now.minus(DAY), "PASSED");
        gate("DEV", devConnection, now.minus(DAY.multipliedBy(9)), "PASSED");
        gate("PRD", prdConnection, now.minus(DAY.multipliedBy(9)).plusSeconds(60), "BLOCKED");

        devConnectionId = jdbc.sql("select id from db_connection where workspace_id = :ws and slug = :s")
                .param("ws", workspaceId).param("s", devConnection).query(UUID.class).single();
        drift(now.minus(DAY.multipliedBy(4)), true, 2, 0, 1);
        drift(now.minus(DAY.multipliedBy(3)), false, 0, 0, 0);
        drift(now.minus(DAY.multipliedBy(2)), false, 0, 0, 0);
        drift(now.minus(DAY.multipliedBy(10)), true, 1, 0, 0);
        drift(now.minus(DAY.multipliedBy(11)), false, 0, 0, 0);
    }

    private void gate(String env, String connection, Instant at, String result) {
        String payload = payloadAt(env, UUID.randomUUID(), project, connection, uniqueUrl(), "update", "SUCCESS", at,
                changeset("SUP-" + UUID.randomUUID().toString().substring(0, 4), "EXECUTED"))
                .replace("\"error\":", "\"policy\": { \"gateResult\": \"" + result + "\", \"findings\": [] }, \"error\":");
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token, payload);
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    private void drift(Instant at, boolean hasDrift, int added, int removed, int modified) {
        jdbc.sql("""
                insert into drift_run (id, db_connection_id, reference_type, status, has_drift, added, removed, modified, started_at, ended_at)
                values (:id, :c, 'SNAPSHOT', 'SUCCESS', :drift, :a, :r, :m, :at, :at)
                """).param("id", UUID.randomUUID()).param("c", devConnectionId).param("drift", hasDrift)
                .param("a", added).param("r", removed).param("m", modified).param("at", Timestamp.from(at)).update();
    }

    private void rebuild() {
        metrics.rebuild(workspaceId, LocalDate.now(ZoneOffset.UTC).minusDays(14), LocalDate.now(ZoneOffset.UTC));
    }

    private JsonNode dashboard() {
        HttpResponse<String> response = callIn(workspace, "GET", API + "/dashboard?period=7d", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    // ------------------------------------------------------------------ policies

    @Test
    @DisplayName("the policy band: pass rate, blocked in production, with the change against the previous window")
    void policies() {
        rebuild();

        JsonNode policies = dashboard().get("policies");

        // current: 5 evaluations, 2 passed (the PASSED DEV and the PASSED PRD)
        JsonNode passRate = policies.get("passRate");
        assertThat(passRate.get("part").asLong()).isEqualTo(2);
        assertThat(passRate.get("whole").asLong()).isEqualTo(5);
        assertThat(passRate.get("value").asDouble()).isEqualTo(40.0);
        // previous: 2 evaluations, 1 passed = 50%, so 40 - 50 = -10 points
        assertThat(passRate.get("trendPts").asDouble()).isEqualTo(-10.0);

        assertThat(policies.get("blocked").get("count").asLong()).as("only the production block").isEqualTo(1);
        assertThat(policies.get("blocked").get("trendAbs").asLong()).as("one before, one now").isZero();
    }

    @Test
    @DisplayName("coverage: a default package covers every database; without one, only databases with a package on a stage")
    void coverage() {
        rebuild();
        JsonNode without = dashboard().get("policies");
        assertThat(without.get("databaseCoverage").get("total").asLong()).isEqualTo(2);
        assertThat(without.get("databaseCoverage").get("covered").asLong()).isZero();

        callIn(workspace, "POST", API + "/policy-packages", "{\"name\":\"default\"}");

        JsonNode with = dashboard().get("policies");
        assertThat(with.get("databaseCoverage").get("covered").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("with nothing evaluated there is no pass rate to invent")
    void nothingEvaluated() {
        // no rebuild: metric_daily has no rows for this workspace
        JsonNode policies = dashboard().get("policies");

        assertThat(policies.get("passRate").get("value")).isNull();
        assertThat(policies.get("blocked").get("count").asLong()).isZero();
    }

    // --------------------------------------------------------------------- drift

    @Test
    @DisplayName("the drift band: rate, detected, items split into added, removed and modified, and monitoring")
    void drift() {
        rebuild();

        JsonNode drift = dashboard().get("drift");

        assertThat(drift.get("driftRate").get("part").asLong()).isEqualTo(1);
        assertThat(drift.get("driftRate").get("whole").asLong()).isEqualTo(3);
        assertThat(drift.get("driftRate").get("value").asDouble()).isEqualTo(33.3);
        // previous: 1 of 2 = 50% -> -16.7 points
        assertThat(drift.get("driftRate").get("trendPts").asDouble()).isEqualTo(-16.7);
        assertThat(drift.get("detected").get("count").asLong()).isEqualTo(1);
        assertThat(drift.get("items").get("total").asLong()).isEqualTo(3);
        assertThat(drift.get("items").get("added").asLong()).isEqualTo(2);
        assertThat(drift.get("items").get("modified").asLong()).isEqualTo(1);
        assertThat(drift.get("items").get("trendAbs").asLong()).as("3 now, 1 before").isEqualTo(2);
        assertThat(drift.get("monitoring").get("total").asLong()).isEqualTo(2);
    }

    // ------------------------------------------------------------------- the table

    @Test
    @DisplayName("rebuilding is idempotent, and picks up what arrived since")
    void rebuildIsIdempotentAndCurrent() {
        rebuild();
        long first = jdbc.sql("select coalesce(sum(checks_run), 0) from metric_daily where workspace_id = :ws")
                .param("ws", workspaceId).query(Long.class).single();
        rebuild();
        long second = jdbc.sql("select coalesce(sum(checks_run), 0) from metric_daily where workspace_id = :ws")
                .param("ws", workspaceId).query(Long.class).single();
        assertThat(second).isEqualTo(first).isEqualTo(7);

        gate("DEV", devConnection, now.minusSeconds(30), "PASSED");
        rebuild();

        assertThat(dashboard().get("policies").get("passRate").get("whole").asLong()).isEqualTo(6);
    }

    @Test
    @DisplayName("metric_daily keeps the deploy figures per day and dimension, drift on its own row")
    void tableShape() {
        rebuild();

        long deployments = jdbc.sql("select coalesce(sum(deployments), 0) from metric_daily where workspace_id = :ws")
                .param("ws", workspaceId).query(Long.class).single();
        long driftRuns = jdbc.sql("select coalesce(sum(drift_runs), 0) from metric_daily where workspace_id = :ws")
                .param("ws", workspaceId).query(Long.class).single();

        assertThat(deployments).isEqualTo(7);
        assertThat(driftRuns).isEqualTo(5);
    }

    @Test
    @DisplayName("the jobs rebuild every workspace and are recorded")
    void jobs() {
        assertThat(jobs.run("metrics-recent", 2)).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.sql("select count(*) from job_run where job = 'metrics-recent'").query(Long.class).single()).isGreaterThan(0);
    }
}
