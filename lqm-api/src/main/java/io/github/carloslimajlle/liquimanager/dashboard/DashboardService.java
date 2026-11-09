package io.github.carloslimajlle.liquimanager.dashboard;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.carloslimajlle.liquimanager.common.Labels;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.access.ReadScope;

/**
 * Operations Dashboard (screen 01), delivery band: the DORA-style indicators of
 * docs/telas/01 § Fórmulas, computed live from {@code operation} and
 * {@code changeset_state}.
 *
 * <p>The delivery band is live on purpose: aggregating the period on demand keeps the numbers
 * identical to a hand-written SQL check, and the cycle time is a median, which cannot be summed
 * from daily rows. The cost is a scan of the period, which is why it is capped at 90 days.
 *
 * <p>The policy and drift bands read {@code metric_daily} (E16), filled by {@link MetricsService}.
 *
 * <p>Definitions, from the spec: a DEPLOY is a {@code database-change} operation whose
 * command is update/update-to-tag/update-count/flow and which either executed something
 * or failed. Operations that only found everything already applied are not deploys.
 */
@Service
public class DashboardService {

    private static final List<String> PERIODS = List.of("7d", "30d", "90d");

    private final JdbcClient jdbc;
    private final MetricsService metrics;

    public DashboardService(JdbcClient jdbc, MetricsService metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /** What one window holds, before it is turned into the response shape. */
    record Window(long deploys, long failed, long successfulDeploys, long rollbacks,
                  long cycleChangesets, Double cycleMedianHours) {

        Double failureRate() {
            return deploys == 0 ? null : failed * 100.0 / deploys;
        }

        Double rollbackRate() {
            return successfulDeploys == 0 ? null : rollbacks * 100.0 / successfulDeploys;
        }
    }

    public static int days(String period) {
        String effective = period == null || period.isBlank() ? "7d" : period;
        if (!PERIODS.contains(effective)) {
            throw LqmException.invalid("period must be one of " + String.join(", ", PERIODS));
        }
        return Integer.parseInt(effective.substring(0, effective.length() - 1));
    }

    public Map<String, Object> dashboard(UUID workspace, String period, Instant now) {
        return dashboard(workspace, period, now, ReadScope.Visible.everything());
    }

    /**
     * The dashboard for what the caller may read. The deploy figures and the header are computed over the connections in scope; the
     * policy and drift bands come from a daily table that keeps no connection, so a restricted caller does not get them
     * ({@code restricted: true}) rather than the totals of the whole workspace next to a list of three rows.
     */
    public Map<String, Object> dashboard(UUID workspace, String period, Instant now, ReadScope.Visible visible) {
        int days = days(period);
        Instant from = now.minus(Duration.ofDays(days));
        Instant previousFrom = from.minus(Duration.ofDays(days));

        Window current = window(workspace, from, now, visible);
        Window previous = window(workspace, previousFrom, from, visible);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", Map.of("days", days, "from", from, "to", now));
        out.put("header", header(workspace, from, now, visible));
        out.put("restricted", !visible.all());

        Map<String, Object> dora = new LinkedHashMap<>();
        dora.put("deployFrequency", deployFrequency(current, previous, days));
        dora.put("changeFailureRate", rate(current.failed(), current.deploys(),
                current.failureRate(), previous.failureRate()));
        dora.put("rollbackRate", rate(current.rollbacks(), current.successfulDeploys(),
                current.rollbackRate(), previous.rollbackRate()));
        dora.put("cycleTime", cycleTime(current, previous));
        out.put("dora", dora);

        if (!visible.all()) {
            return out;
        }
        MetricsService.Totals currentTotals = metrics.totals(workspace, from, now);
        MetricsService.Totals previousTotals = metrics.totals(workspace, previousFrom, from.minus(Duration.ofDays(1)));
        Map<String, Object> coverage = metrics.coverage(workspace);
        out.put("policies", policies(currentTotals, previousTotals, coverage));
        out.put("drift", drift(workspace, from, now, currentTotals, previousTotals, coverage));
        return out;
    }

    // ----------------------------------------------------------------- bands

    private Map<String, Object> policies(MetricsService.Totals current, MetricsService.Totals previous,
                                         Map<String, Object> coverage) {
        Map<String, Object> out = new LinkedHashMap<>();
        Double rate = current.checksRun() == 0 ? null : current.checksPassed() * 100.0 / current.checksRun();
        Double before = previous.checksRun() == 0 ? null : previous.checksPassed() * 100.0 / previous.checksRun();
        out.put("passRate", rate(current.checksPassed(), current.checksRun(), rate, before));
        Map<String, Object> blocked = new LinkedHashMap<>();
        blocked.put("count", current.blocked());
        blocked.put("trendAbs", current.blocked() - previous.blocked());
        out.put("blocked", blocked);
        out.put("databaseCoverage", Map.of("covered", coverage.get("databasesCovered"), "total", coverage.get("databases")));
        out.put("changelogCoverage", Map.of("covered", coverage.get("changelogsCovered"), "total", coverage.get("changelogs")));
        return out;
    }

    private Map<String, Object> drift(UUID workspace, Instant from, Instant to, MetricsService.Totals current,
                                      MetricsService.Totals previous, Map<String, Object> coverage) {
        Map<String, Object> out = new LinkedHashMap<>();
        Double rate = current.driftRuns() == 0 ? null : current.driftFound() * 100.0 / current.driftRuns();
        Double before = previous.driftRuns() == 0 ? null : previous.driftFound() * 100.0 / previous.driftRuns();
        out.put("driftRate", rate(current.driftFound(), current.driftRuns(), rate, before));
        out.put("detected", Map.of("count", current.driftFound(), "trendAbs", current.driftFound() - previous.driftFound()));

        // The sub-text splits the items into added, removed and modified, which the daily table does not keep.
        Map<String, Object> split = jdbc.sql("""
                select coalesce(sum(r.added) filter (where r.has_drift), 0) as added,
                       coalesce(sum(r.removed) filter (where r.has_drift), 0) as removed,
                       coalesce(sum(r.modified) filter (where r.has_drift), 0) as modified
                  from drift_run r join db_connection c on c.id = r.db_connection_id
                 where c.workspace_id = :ws and r.started_at >= :from and r.started_at < :to
                """).param("ws", workspace).param("from", utc(from)).param("to", utc(to)).query().singleRow();
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("total", current.driftItems());
        items.put("added", number(split.get("added")));
        items.put("removed", number(split.get("removed")));
        items.put("modified", number(split.get("modified")));
        items.put("trendAbs", current.driftItems() - previous.driftItems());
        out.put("items", items);
        out.put("monitoring", Map.of("monitored", coverage.get("monitored"), "total", coverage.get("databases")));
        return out;
    }

    // ----------------------------------------------------------------- KPIs

    private Map<String, Object> deployFrequency(Window current, Window previous, int days) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("perDay", round2(current.deploys() / (double) days));
        out.put("deploys", current.deploys());
        // Good if it goes UP. No previous deploys means no base to compare with.
        out.put("trendPct", previous.deploys() == 0 ? null
                : round1((current.deploys() - previous.deploys()) * 100.0 / previous.deploys()));
        return out;
    }

    /** Failure and rollback rates share a shape: a percentage, its parts, and the change in points. */
    private Map<String, Object> rate(long part, long whole, Double rate, Double previousRate) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", rate == null ? null : round1(rate));
        out.put("part", part);
        out.put("whole", whole);
        // Good if it goes DOWN. A change in percentage POINTS, not a percentage of a percentage.
        out.put("trendPts", rate == null || previousRate == null ? null : round1(rate - previousRate));
        return out;
    }

    private Map<String, Object> cycleTime(Window current, Window previous) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hours", current.cycleMedianHours() == null ? null : round1(current.cycleMedianHours()));
        out.put("changesets", current.cycleChangesets());
        out.put("trendPct", current.cycleMedianHours() == null || previous.cycleMedianHours() == null
                || previous.cycleMedianHours() == 0 ? null
                : round1((current.cycleMedianHours() - previous.cycleMedianHours()) * 100.0
                        / previous.cycleMedianHours()));
        return out;
    }

    // -------------------------------------------------------------- queries

    /**
     * One window of deploy figures and the cycle time. Two queries, because the cycle
     * time is about CHANGESETS reaching production and the rest is about OPERATIONS.
     */
    private Window window(UUID workspace, Instant from, Instant to, ReadScope.Visible visible) {
        ReadScope.Filter ofOperation = visible.connections("o.db_connection_id", "visibleConnections");
        ReadScope.Filter ofState = visible.connections("c.id", "visibleConnections");
        Map<String, Object> ops = jdbc.sql("""
                select count(*) filter (where is_deploy) as deploys,
                       count(*) filter (where is_deploy and status = 'FAILURE') as failed,
                       count(*) filter (where is_deploy and status = 'SUCCESS') as ok,
                       count(*) filter (where command like 'rollback%' and status = 'SUCCESS') as rollbacks
                  from (select o.status, o.command,
                               (o.type = 'database-change'
                                and ((o.command in ('update', 'update-to-tag', 'update-count')
                                      and (o.count_executed > 0 or o.status = 'FAILURE'))
                                     -- A flow is a deploy only when it carries the changesets itself. The CLI's flow
                                     -- does not: its stages are the operations, and counting both would double every number.
                                     or (o.command = 'flow' and o.count_executed > 0))) as is_deploy
                          from operation o
                         where o.workspace_id = :ws and o.started_at >= :from and o.started_at < :to
                """ + ofOperation.sql() + """
                        ) x
                """)
                .param("ws", workspace).param("from", utc(from)).param("to", utc(to)).params(ofOperation.params())
                .query().singleRow();

        Map<String, Object> cycle = jdbc.sql("""
                with prod as (
                    select s.changeset_id, min(s.deployed_at) as prod_at
                      from changeset_state s
                      join db_connection c on c.id = s.db_connection_id
                      join environment e on e.id = c.environment_id
                     where c.workspace_id = :ws and e.is_production and s.status = 'EXECUTED'
                       and s.deployed_at >= :from and s.deployed_at < :to""" + ofState.sql() + """
                     group by s.changeset_id),
                pre as (
                    select s.changeset_id, min(s.deployed_at) as first_at
                      from changeset_state s
                      join db_connection c on c.id = s.db_connection_id
                      join environment e on e.id = c.environment_id
                     where c.workspace_id = :ws and not e.is_production and s.status = 'EXECUTED'
                       and s.deployed_at is not null""" + ofState.sql() + """
                     group by s.changeset_id)
                select count(*) as changesets,
                       percentile_cont(0.5) within group (
                           order by extract(epoch from (prod.prod_at - pre.first_at)) / 3600.0) as median_hours
                  from prod join pre on pre.changeset_id = prod.changeset_id
                 where prod.prod_at >= pre.first_at
                """)
                .param("ws", workspace).param("from", utc(from)).param("to", utc(to)).params(ofState.params())
                .query().singleRow();

        Object median = cycle.get("median_hours");
        return new Window(number(ops.get("deploys")), number(ops.get("failed")), number(ops.get("ok")),
                number(ops.get("rollbacks")), number(cycle.get("changesets")),
                median == null ? null : ((Number) median).doubleValue());
    }

    private Map<String, Object> header(UUID workspace, Instant from, Instant to, ReadScope.Visible visible) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("teams", count("select count(*) from team where workspace_id = :ws", workspace, ReadScope.Filter.NONE));
        out.put("connections", count("select count(*) from db_connection where workspace_id = :ws and archived_at is null", workspace,
                visible.connections("id", "visibleConnections")));
        out.put("pipelines", count("select count(*) from pipeline where workspace_id = :ws", workspace,
                visible.pipelines("id", "visiblePipelines")));
        ReadScope.Filter operations = visible.connections("db_connection_id", "visibleConnections");
        out.put("operations", jdbc.sql("""
                select count(*) from operation
                 where workspace_id = :ws and started_at >= :from and started_at < :to""" + operations.sql())
                .param("ws", workspace).param("from", utc(from)).param("to", utc(to)).params(operations.params())
                .query(Long.class).single());
        return out;
    }

    private long count(String sql, UUID workspace, ReadScope.Filter visible) {
        return jdbc.sql(sql + visible.sql()).param("ws", workspace).params(visible.params()).query(Long.class).single();
    }

    // -------------------------------------------------------------- activity

    /**
     * The latest operations as the dashboard table shows them: who (team), what
     * (a changeset, or "a package" when several), where, and how it ended.
     */
    public List<Map<String, Object>> activity(UUID workspace, int limit, UUID project) {
        return activity(workspace, limit, project, ReadScope.Visible.everything());
    }

    public List<Map<String, Object>> activity(UUID workspace, int limit, UUID project, ReadScope.Visible visible) {
        ReadScope.Filter readable = visible.connections("o.db_connection_id", "visibleConnections");
        return jdbc.sql("""
                select o.id, o.command, o.status, o.type, o.started_at, o.labels, o.source,
                       o.count_executed, o.count_previously_run, o.count_failed,
                       t.name as team, c.display_name as connection, e.code as environment,
                       o.project_id, p.slug as project, o.changelog_id,
                       (select cs.id from operation_changeset oc join changeset cs on cs.id = oc.changeset_id
                         where oc.operation_id = o.id order by oc.exec_order nulls last, cs.cs_id limit 1) as first_changeset_id,
                       (select count(*) from operation_changeset oc where oc.operation_id = o.id) as changeset_count,
                       (select cs.cs_id from operation_changeset oc join changeset cs on cs.id = oc.changeset_id
                         where oc.operation_id = o.id order by oc.exec_order nulls last, cs.cs_id limit 1) as first_changeset
                  from operation o
                  join db_connection c on c.id = o.db_connection_id
                  join environment e on e.id = c.environment_id
                  left join project p on p.id = o.project_id
                  left join team t on t.id = p.team_id
                 where o.workspace_id = :ws and (cast(:project as uuid) is null or o.project_id = :project)
                """ + readable.sql() + """
                 order by o.started_at desc, o.id
                 limit :limit
                """)
                .param("ws", workspace).param("limit", limit).param("project", project).params(readable.params())
                .query().listOfRows().stream().map(DashboardService::activityRow).toList();
    }

    private static Map<String, Object> activityRow(Map<String, Object> row) {
        long changesets = number(row.get("changeset_count"));
        String command = (String) row.get("command");

        // What the operation was about: one changeset by name, several as "release X package",
        // and a diff has no changesets at all, only a schema snapshot.
        String subject;
        if (command.startsWith("diff")) {
            subject = "schema snapshot";
        } else if (changesets == 1) {
            subject = (String) row.get("first_changeset");
        } else if (changesets > 1) {
            String release = Labels.release((String) row.get("labels"));
            subject = (release == null ? "changesets" : release.replace("release-", "")) + " package";
        } else {
            subject = null;
        }

        Long scope = command.startsWith("diff") ? null
                : number(row.get("count_executed")) + number(row.get("count_previously_run"))
                        + number(row.get("count_failed"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("startedAt", ((java.sql.Timestamp) row.get("started_at")).toInstant());
        out.put("team", row.get("team"));
        out.put("project", row.get("project"));
        out.put("projectId", row.get("project_id"));
        out.put("changelogId", row.get("changelog_id"));
        out.put("changesetId", changesets > 0 ? row.get("first_changeset_id") : null);
        out.put("changesetCount", changesets);
        out.put("subject", subject);
        out.put("connection", row.get("connection"));
        out.put("environment", row.get("environment"));
        out.put("command", command);
        out.put("scope", scope);
        out.put("status", row.get("status"));
        return out;
    }

    // --------------------------------------------------------------- helpers

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
