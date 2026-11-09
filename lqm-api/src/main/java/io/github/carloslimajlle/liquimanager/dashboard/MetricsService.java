package io.github.carloslimajlle.liquimanager.dashboard;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code metric_daily} (E16): what the policy and drift bands of the dashboard read, so that a 90-day window
 * is a sum over a few thousand small rows instead of a scan of every operation.
 *
 * <p>Rebuilding a range deletes it and writes it again from the source tables, in two statements: one over
 * operations (deploys, failures, rollbacks, gate results) and one over drift runs. That makes it idempotent
 * and self-healing: whatever the table said before, running it for a day makes the day right.
 *
 * <p>Days are UTC. {@code blocked} counts only gates that blocked in a PRODUCTION environment, since that is
 * what the screen means by "blocked before production". The cycle time (a median, which is not summable) is
 * NOT materialised here: the delivery band still computes it live.
 */
@Service
public class MetricsService {

    private final JdbcClient jdbc;

    MetricsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Rewrites {@code from} through {@code to}, both inclusive. */
    @Transactional
    public void rebuild(UUID workspace, LocalDate from, LocalDate to) {
        Timestamp start = Timestamp.from(from.atStartOfDay().toInstant(ZoneOffset.UTC));
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));

        jdbc.sql("delete from metric_daily where workspace_id = :ws and day between :from and :to")
                .param("ws", workspace).param("from", java.sql.Date.valueOf(from)).param("to", java.sql.Date.valueOf(to)).update();

        jdbc.sql("""
                insert into metric_daily (day, workspace_id, project_id, db_connection_id, environment_id, deployments,
                    failed_deployments, rollbacks, checks_run, checks_passed, blocked, drift_runs, drift_found, drift_items, dim_key)
                select (o.started_at at time zone 'UTC')::date as day, o.workspace_id, o.project_id, o.db_connection_id,
                       c.environment_id,
                       count(*) filter (where is_deploy),
                       count(*) filter (where is_deploy and o.status = 'FAILURE'),
                       count(*) filter (where o.command like 'rollback%' and o.status = 'SUCCESS'),
                       count(*) filter (where o.gate_result is not null),
                       count(*) filter (where o.gate_result = 'PASSED'),
                       count(*) filter (where o.gate_result = 'BLOCKED' and e.is_production),
                       0, 0, 0,
                       md5(coalesce(o.project_id::text, '') || '|' || o.db_connection_id::text || '|' || c.environment_id::text)
                  from (select o.*, (o.type = 'database-change'
                                     and ((o.command in ('update', 'update-to-tag', 'update-count')
                                           and (o.count_executed > 0 or o.status = 'FAILURE'))
                                          or (o.command = 'flow' and o.count_executed > 0))) as is_deploy
                          from operation o where o.workspace_id = :ws and o.started_at >= :start and o.started_at < :end) o
                  join db_connection c on c.id = o.db_connection_id
                  join environment e on e.id = c.environment_id
                 group by 1, o.workspace_id, o.project_id, o.db_connection_id, c.environment_id
                """).param("ws", workspace).param("start", start).param("end", end).update();

        // Drift runs have no project; their rows are the ones whose project_id is null, and may already exist (the
        // drift operation itself), so this adds to them rather than assuming they are new.
        jdbc.sql("""
                insert into metric_daily (day, workspace_id, project_id, db_connection_id, environment_id, deployments,
                    failed_deployments, rollbacks, checks_run, checks_passed, blocked, drift_runs, drift_found, drift_items, dim_key)
                select (r.started_at at time zone 'UTC')::date, c.workspace_id, null, r.db_connection_id, c.environment_id,
                       0, 0, 0, 0, 0, 0,
                       count(*) filter (where r.status = 'SUCCESS'),
                       count(*) filter (where r.has_drift),
                       coalesce(sum(r.added + r.removed + r.modified) filter (where r.has_drift), 0),
                       md5('|' || r.db_connection_id::text || '|' || c.environment_id::text)
                  from drift_run r join db_connection c on c.id = r.db_connection_id
                 where c.workspace_id = :ws and r.started_at >= :start and r.started_at < :end
                 group by 1, c.workspace_id, r.db_connection_id, c.environment_id
                on conflict (day, workspace_id, dim_key) do update
                   set drift_runs = excluded.drift_runs, drift_found = excluded.drift_found, drift_items = excluded.drift_items
                """).param("ws", workspace).param("start", start).param("end", end).update();
    }

    /** The recent days, for the frequent job. */
    public void rebuildRecent(UUID workspace, int days) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        rebuild(workspace, today.minusDays(Math.max(0, days - 1)), today);
    }

    // ---------------------------------------------------------------- reading

    /** What a window of days adds up to. */
    record Totals(long checksRun, long checksPassed, long blocked, long driftRuns, long driftFound, long driftItems) {
    }

    Totals totals(UUID workspace, Instant from, Instant to) {
        var row = jdbc.sql("""
                select coalesce(sum(checks_run), 0) as run, coalesce(sum(checks_passed), 0) as passed,
                       coalesce(sum(blocked), 0) as blocked, coalesce(sum(drift_runs), 0) as dr,
                       coalesce(sum(drift_found), 0) as df, coalesce(sum(drift_items), 0) as di
                  from metric_daily
                 where workspace_id = :ws and day >= :from and day < :to
                """).param("ws", workspace)
                .param("from", java.sql.Date.valueOf(from.atOffset(ZoneOffset.UTC).toLocalDate()))
                .param("to", java.sql.Date.valueOf(to.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1)))
                .query().singleRow();
        return new Totals(n(row.get("run")), n(row.get("passed")), n(row.get("blocked")), n(row.get("dr")), n(row.get("df")),
                n(row.get("di")));
    }

    /** Coverage figures the bands show as "X of Y". Not time series: they are the state right now. */
    Map<String, Object> coverage(UUID workspace) {
        boolean hasDefault = jdbc.sql("select count(*) from policy_package where workspace_id = :ws and name = 'default'")
                .param("ws", workspace).query(Long.class).single() > 0;
        long connections = jdbc.sql("select count(*) from db_connection where workspace_id = :ws and archived_at is null")
                .param("ws", workspace).query(Long.class).single();
        // A default package covers every database; without one, only the databases with a package on one of their stages.
        long coveredConnections = hasDefault ? connections : jdbc.sql("""
                select count(distinct ps.db_connection_id) from pipeline_stage ps join pipeline p on p.id = ps.pipeline_id
                  join db_connection c on c.id = ps.db_connection_id
                 where p.workspace_id = :ws and ps.policy_package_id is not null and c.archived_at is null
                """).param("ws", workspace).query(Long.class).single();
        long changelogs = jdbc.sql("select count(*) from changelog cl join project p on p.id = cl.project_id where p.workspace_id = :ws")
                .param("ws", workspace).query(Long.class).single();
        long coveredChangelogs = hasDefault ? changelogs : jdbc.sql("""
                select count(*) from changelog cl join project p on p.id = cl.project_id
                 where p.workspace_id = :ws and cl.policy_package_id is not null
                """).param("ws", workspace).query(Long.class).single();
        long monitored = jdbc.sql("select count(*) from db_connection where workspace_id = :ws and archived_at is null and drift_enabled")
                .param("ws", workspace).query(Long.class).single();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("databasesCovered", coveredConnections);
        out.put("databases", connections);
        out.put("changelogsCovered", coveredChangelogs);
        out.put("changelogs", changelogs);
        out.put("monitored", monitored);
        return out;
    }

    private static long n(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }
}
