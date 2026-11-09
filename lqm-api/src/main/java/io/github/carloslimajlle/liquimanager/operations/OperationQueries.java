package io.github.carloslimajlle.liquimanager.operations;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import io.github.carloslimajlle.liquimanager.access.ReadScope;
import io.github.carloslimajlle.liquimanager.common.Labels;

/**
 * Read side of operations (screens 05 and 02).
 *
 * <p>Plain SQL on purpose: these are reporting queries across a partitioned table,
 * and the filters are optional. The WHERE clause is assembled from fragments only
 * for the filters actually present, so the planner can use the indexes on
 * (workspace_id, started_at) and (db_connection_id, started_at) instead of fighting
 * a catch-all {@code :param is null or ...} predicate. Values are ALWAYS bound
 * parameters; the only text spliced into SQL comes from the fixed allow-lists below.
 */
@Repository
public class OperationQueries {

    /** Sort keys the API accepts -> the column they order by. Nothing else reaches the SQL. */
    private static final Map<String, String> SORTABLE = Map.of(
            "startedAt", "o.started_at",
            "durationMs", "o.duration_ms",
            "status", "o.status",
            "command", "o.command",
            "type", "o.type",
            "executedBy", "o.executed_by",
            "connection", "c.slug",
            "project", "p.slug");

    private static final String FROM = """
              from operation o
              join db_connection c on c.id = o.db_connection_id
              join environment e on e.id = c.environment_id
              left join project p on p.id = o.project_id
              left join changelog cl on cl.id = o.changelog_id
            """;

    private final JdbcClient jdbc;

    public OperationQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ shapes

    public record Page<T>(List<T> content, int page, int size, long totalElements) {
    }

    public record Kpis(long total, Double trendPct, Double successRate, long succeeded,
                       Long medianDurationMs, long caughtBeforeProd, long failuresNonProd) {
    }

    // -------------------------------------------------------------------- list

    public Page<Map<String, Object>> list(UUID workspace, OperationFilter filter, int page, int size, String sort) {
        return list(workspace, filter, page, size, sort, ReadScope.Visible.everything());
    }

    /** As above, limited to the operations on connections the caller may read. */
    public Page<Map<String, Object>> list(UUID workspace, OperationFilter filter, int page, int size, String sort, ReadScope.Visible visible) {
        Where where = where(workspace, filter, filter.from(), filter.to(), visible);

        long total = jdbc.sql("select count(*) " + FROM + where.sql())
                .params(where.params()).query(Long.class).single();

        String order = orderBy(sort);
        List<Map<String, Object>> rows = jdbc.sql("""
                select o.id, o.command, o.type, o.status, o.source, o.executed_by, o.started_at,
                       o.duration_ms, o.labels, p.slug as project, c.slug as connection,
                       e.code as environment, cl.name as changelog,
                       o.project_id, o.changelog_id,
                       (select count(*) from operation_changeset oc where oc.operation_id = o.id) as changeset_count,
                       (select cs.id from operation_changeset oc join changeset cs on cs.id = oc.changeset_id
                         where oc.operation_id = o.id order by oc.exec_order nulls last, cs.cs_id limit 1) as first_changeset_id,
                       (select cs.cs_id from operation_changeset oc join changeset cs on cs.id = oc.changeset_id
                         where oc.operation_id = o.id order by oc.exec_order nulls last, cs.cs_id limit 1) as first_changeset
                """ + FROM + where.sql() + " order by " + order + ", o.id limit :limit offset :offset")
                .params(where.params())
                .param("limit", size).param("offset", (long) page * size)
                .query().listOfRows().stream().map(OperationQueries::summary).toList();

        return new Page<>(rows, page, size, total);
    }

    // -------------------------------------------------------------------- KPIs

    /**
     * KPIs for the filtered set, over exactly the window the filters describe. With no
     * {@code from} the window is unbounded (all time) and there is no trend; the "last 7
     * days" default belongs to the screen, which sends the {@code from} explicitly. A
     * server-side default would make "all time" on screen silently mean "a week".
     *
     * <p>With a {@code from}, the trend compares the window with the one of the same
     * length immediately before it.
     *
     * <p>Definitions (the spec names the cards but not the arithmetic, so they are
     * fixed here): the success rate is over FINISHED operations only — SUCCESS, FAILURE
     * and BLOCKED — so a RUNNING or CANCELED one neither helps nor hurts it. "Caught
     * before production" counts failures and policy blocks in environments that are
     * not production: the changes the pipeline stopped before they could hurt.
     */
    public Kpis kpis(UUID workspace, OperationFilter filter, Instant now) {
        return kpis(workspace, filter, now, ReadScope.Visible.everything());
    }

    public Kpis kpis(UUID workspace, OperationFilter filter, Instant now, ReadScope.Visible visible) {
        Instant from = filter.from();
        Where current = where(workspace, filter, from, filter.to(), visible);

        Map<String, Object> row = jdbc.sql("""
                select count(*) as total,
                       count(*) filter (where o.status = 'SUCCESS') as succeeded,
                       count(*) filter (where o.status in ('SUCCESS', 'FAILURE', 'BLOCKED')) as finished,
                       percentile_cont(0.5) within group (order by o.duration_ms)
                           filter (where o.duration_ms is not null) as median,
                       count(*) filter (where o.status in ('FAILURE', 'BLOCKED') and not e.is_production) as caught,
                       count(*) filter (where o.status = 'FAILURE' and not e.is_production) as failures_non_prod
                """ + FROM + current.sql())
                .params(current.params()).query().singleRow();

        long total = number(row.get("total"));
        long finished = number(row.get("finished"));
        long succeeded = number(row.get("succeeded"));

        Double trend = null;
        if (from != null) {
            Instant to = filter.to() != null ? filter.to() : now;
            Duration window = Duration.between(from, to);
            Where previous = where(workspace, filter, from.minus(window), from, visible);
            long before = jdbc.sql("select count(*) " + FROM + previous.sql())
                    .params(previous.params()).query(Long.class).single();
            trend = before == 0 ? null : round((total - before) * 100.0 / before);
        }
        Double successRate = finished == 0 ? null : round(succeeded * 100.0 / finished);
        Object median = row.get("median");

        return new Kpis(total, trend, successRate, succeeded,
                median == null ? null : Math.round(((Number) median).doubleValue()),
                number(row.get("caught")), number(row.get("failures_non_prod")));
    }

    // ------------------------------------------------------------------ detail

    public Optional<Map<String, Object>> detail(UUID workspace, UUID id) {
        List<Map<String, Object>> rows = jdbc.sql("""
                select o.id, o.command, o.type, o.status, o.source, o.executed_by, o.started_at, o.ended_at,
                       o.duration_ms, o.deployment_id, o.contexts, o.labels, o.tag, o.git_commit, o.git_branch,
                       o.ci_pipeline_id, o.ci_job_url, o.gate_result, o.error_message, o.exit_code,
                       o.count_executed, o.count_previously_run, o.count_filtered, o.count_failed,
                       o.liquibase_version, o.collector, o.collector_version, o.host, o.log_ref, o.sql_ref,
                       p.slug as project, c.slug as connection, c.engine, c.db_version,
                       e.code as environment, cl.name as changelog,
                       o.project_id, o.changelog_id,
                       (select count(*) from operation_changeset oc where oc.operation_id = o.id) as changeset_count,
                       (select cs.id from operation_changeset oc join changeset cs on cs.id = oc.changeset_id
                         where oc.operation_id = o.id order by oc.exec_order nulls last, cs.cs_id limit 1) as first_changeset_id,
                       (select cs.cs_id from operation_changeset oc join changeset cs on cs.id = oc.changeset_id
                         where oc.operation_id = o.id order by oc.exec_order nulls last, cs.cs_id limit 1) as first_changeset
                """ + FROM + " where o.workspace_id = :ws and o.id = :id")
                .param("ws", workspace).param("id", id).query().listOfRows();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);

        Map<String, Object> out = summary(row);
        out.put("endedAt", instant(row.get("ended_at")));
        out.put("deploymentId", row.get("deployment_id"));
        out.put("contexts", row.get("contexts"));
        out.put("labels", row.get("labels"));
        out.put("tag", row.get("tag"));
        out.put("gitCommit", row.get("git_commit"));
        out.put("gitBranch", row.get("git_branch"));
        out.put("ciPipelineId", row.get("ci_pipeline_id"));
        out.put("ciJobUrl", row.get("ci_job_url"));
        out.put("gateResult", row.get("gate_result"));
        out.put("errorMessage", row.get("error_message"));
        out.put("exitCode", row.get("exit_code"));
        out.put("hasLog", row.get("log_ref") != null);
        out.put("hasSql", row.get("sql_ref") != null);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("executed", row.get("count_executed"));
        summary.put("previouslyRun", row.get("count_previously_run"));
        summary.put("filtered", row.get("count_filtered"));
        summary.put("failed", row.get("count_failed"));
        out.put("summary", summary);

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("engine", row.get("engine"));
        runtime.put("dbVersion", row.get("db_version"));
        runtime.put("liquibaseVersion", row.get("liquibase_version"));
        runtime.put("collector", row.get("collector") == null ? null
                : row.get("collector") + (row.get("collector_version") == null ? "" : " " + row.get("collector_version")));
        runtime.put("host", row.get("host"));
        out.put("runtime", runtime);

        out.put("failedChangeset", failedChangeset(id));
        out.put("findings", findings(id));
        return Optional.of(out);
    }

    /**
     * The changeset that broke the operation, with its status in EVERY environment.
     *
     * <p>This is what stands in for AI analysis while that stays a future feature
     * (D-13): "passed in DEV, TST and HML, failed in PRD" already says the problem is
     * the data in PRD, not the changeset.
     */
    private Map<String, Object> failedChangeset(UUID operationId) {
        List<Map<String, Object>> rows = jdbc.sql("""
                select cs.id, cs.cs_id, cs.author, cs.file_path, oc.error_message
                  from operation_changeset oc join changeset cs on cs.id = oc.changeset_id
                 where oc.operation_id = :op and oc.outcome = 'FAILED'
                 order by oc.exec_order nulls last limit 1
                """).param("op", operationId).query().listOfRows();
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);

        List<Map<String, Object>> environments = jdbc.sql("""
                select e.code as environment, s.status, s.deployed_at
                  from changeset_state s
                  join db_connection c on c.id = s.db_connection_id
                  join environment e on e.id = c.environment_id
                 where s.changeset_id = :cs
                 order by e.sort_order, c.slug
                """).param("cs", row.get("id")).query().listOfRows().stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("environment", r.get("environment"));
            item.put("status", r.get("status"));
            item.put("deployedAt", instant(r.get("deployed_at")));
            return item;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("cs_id"));
        out.put("author", row.get("author"));
        out.put("filePath", row.get("file_path"));
        out.put("errorMessage", row.get("error_message"));
        out.put("environments", environments);
        return out;
    }

    private List<Map<String, Object>> findings(UUID operationId) {
        return jdbc.sql("""
                select pc.code as check_code, f.severity, f.result, f.message, f.object_name, cs.cs_id
                  from policy_finding f
                  join policy_check pc on pc.id = f.check_id
                  left join changeset cs on cs.id = f.changeset_id
                 where f.operation_id = :op
                 order by f.severity desc, pc.code
                """).param("op", operationId).query().listOfRows().stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("check", r.get("check_code"));
            item.put("severity", r.get("severity"));
            item.put("result", r.get("result"));
            item.put("message", r.get("message"));
            item.put("objectName", r.get("object_name"));
            item.put("changeset", r.get("cs_id"));
            return item;
        }).toList();
    }

    // -------------------------------------------------------------- changesets

    /** Failures first, then execution order (spec, screen 02). */
    public Page<Map<String, Object>> changesets(UUID operationId, int page, int size) {
        long total = jdbc.sql("select count(*) from operation_changeset where operation_id = :op")
                .param("op", operationId).query(Long.class).single();

        List<Map<String, Object>> rows = jdbc.sql("""
                select cs.cs_id, cs.author, cs.file_path, cs.labels, cs.has_rollback, oc.outcome, oc.filter_reason,
                       oc.exec_order, oc.started_at, oc.duration_ms, oc.error_message
                  from operation_changeset oc join changeset cs on cs.id = oc.changeset_id
                 where oc.operation_id = :op
                 order by (oc.outcome = 'FAILED') desc, oc.exec_order nulls last, cs.cs_id
                 limit :limit offset :offset
                """)
                .param("op", operationId).param("limit", size).param("offset", (long) page * size)
                .query().listOfRows().stream().map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("changesetId", r.get("cs_id"));
                    item.put("author", r.get("author"));
                    item.put("filePath", r.get("file_path"));
                    item.put("outcome", r.get("outcome"));
                    item.put("hasRollback", r.get("has_rollback"));
                    item.put("filterReason", r.get("filter_reason"));
                    item.put("release", Labels.release((String) r.get("labels")));
                    item.put("startedAt", instant(r.get("started_at")));
                    item.put("durationMs", r.get("duration_ms"));
                    item.put("errorMessage", r.get("error_message"));
                    return item;
                }).toList();
        return new Page<>(rows, page, size, total);
    }

    public Optional<String> blobReference(UUID workspace, UUID id, String column) {
        String field = "log".equals(column) ? "log_ref" : "sql_ref";
        return jdbc.sql("select " + field + " from operation where workspace_id = :ws and id = :id")
                .param("ws", workspace).param("id", id).query(String.class).optional();
    }

    // ----------------------------------------------------------------- helpers

    private record Where(String sql, Map<String, Object> params) {
    }

    /** Builds the WHERE for a window, adding a fragment only for each filter that is set. */
    private Where where(UUID workspace, OperationFilter f, Instant from, Instant to, ReadScope.Visible visible) {
        List<String> clauses = new ArrayList<>();
        Map<String, Object> params = new LinkedHashMap<>();
        clauses.add("o.workspace_id = :ws");
        params.put("ws", workspace);
        ReadScope.Filter readable = visible.connections("o.db_connection_id", "visibleConnections");
        if (!readable.sql().isEmpty()) {
            clauses.add(readable.sql().substring(" and ".length()));
            params.putAll(readable.params());
        }

        add(clauses, params, f.status(), "o.status = :status", "status");
        add(clauses, params, f.type(), "o.type = :type", "type");
        add(clauses, params, f.command(), "o.command = :command", "command");
        add(clauses, params, upper(f.env()), "e.code = :env", "env");
        add(clauses, params, f.connection(), "c.slug = :connection", "connection");
        add(clauses, params, f.project(), "p.slug = :project", "project");
        add(clauses, params, f.changelog(), "cl.name = :changelog", "changelog");
        add(clauses, params, f.executedBy(), "o.executed_by = :executedBy", "executedBy");
        add(clauses, params, f.source(), "o.source = :source", "source");
        if (from != null) {
            clauses.add("o.started_at >= :from");
            params.put("from", utc(from));
        }
        if (to != null) {
            clauses.add("o.started_at < :to");
            params.put("to", utc(to));
        }
        if (f.q() != null && !f.q().isBlank()) {
            clauses.add("""
                    (cast(o.id as text) like :q or lower(o.executed_by) like :q or lower(p.slug) like :q
                     or lower(c.slug) like :q or lower(cl.name) like :q
                     or exists (select 1 from operation_changeset oc join changeset cs on cs.id = oc.changeset_id
                                 where oc.operation_id = o.id and lower(cs.cs_id) like :q))
                    """);
            params.put("q", "%" + f.q().trim().toLowerCase(Locale.ROOT) + "%");
        }
        return new Where(" where " + String.join(" and ", clauses), params);
    }

    private static void add(List<String> clauses, Map<String, Object> params, String value, String clause, String name) {
        if (value != null && !value.isBlank()) {
            clauses.add(clause);
            params.put(name, value);
        }
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    /** {@code sort=startedAt,desc}. Unknown keys fall back to the default instead of erroring. */
    static String orderBy(String sort) {
        String key = "startedAt";
        String direction = "desc";
        if (sort != null && !sort.isBlank()) {
            String[] parts = sort.split(",");
            if (SORTABLE.containsKey(parts[0].trim())) {
                key = parts[0].trim();
            }
            if (parts.length > 1 && parts[1].trim().equalsIgnoreCase("asc")) {
                direction = "asc";
            }
        }
        return SORTABLE.get(key) + " " + direction + " nulls last";
    }

    private static Map<String, Object> summary(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("command", row.get("command"));
        out.put("status", row.get("status"));
        out.put("type", row.get("type"));
        out.put("source", row.get("source"));
        out.put("executedBy", row.get("executed_by"));
        out.put("project", row.get("project"));
        out.put("projectId", row.get("project_id"));
        out.put("changelogId", row.get("changelog_id"));
        out.put("changeset", row.get("first_changeset"));
        out.put("changesetId", row.get("first_changeset_id"));
        out.put("changesetCount", row.get("changeset_count"));
        out.put("connection", row.get("connection"));
        out.put("environment", row.get("environment"));
        out.put("changelog", row.get("changelog"));
        out.put("release", Labels.release((String) row.get("labels")));
        out.put("startedAt", instant(row.get("started_at")));
        out.put("durationMs", row.get("duration_ms"));
        out.put("isBreakFix", Labels.isBreakFix((String) row.get("labels")));
        return out;
    }

    private static Instant instant(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : (Instant) value;
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
