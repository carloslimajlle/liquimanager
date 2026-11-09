package io.github.carloslimajlle.liquimanager.enforcement;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.dashboard.DashboardService;

/**
 * What the policy gates decided along a pipeline (screen 06).
 *
 * <p>Honest limits, so the screen is not read as more than it is:
 * <ul>
 *   <li>Only FINDINGS are stored, not every check that passed. "Checks executados" in the
 *       target print therefore cannot be computed; this reports evaluations (operations
 *       that went through a gate) and the pass rate over them.
 *   <li>A stage's gate card shows the last evaluation on that stage's database, whatever
 *       its source.
 *   <li>Break-fix edits are returned; out-of-process and drift panels need the scanner and drift (E14/E15).
 * </ul>
 */
@Service
public class EnforcementService {

    private static final int MAX_FINDINGS = 100;

    private final JdbcClient jdbc;

    public EnforcementService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private record Stage(UUID id, UUID connectionId, String env, String envName, String connection, boolean production) {
    }

    public Map<String, Object> enforcement(UUID workspace, UUID pipelineId, String period, Instant now) {
        int days = DashboardService.days(period);
        Instant from = now.minus(Duration.ofDays(days));

        var found = jdbc.sql("select p.id, p.name, pr.slug as project from pipeline p left join project pr on pr.id = p.project_id where p.id = :id and p.workspace_id = :ws")
                .param("id", pipelineId).param("ws", workspace).query().listOfRows();
        if (found.isEmpty()) {
            throw LqmException.notFound("pipeline");
        }

        List<Stage> stages = jdbc.sql("""
                select ps.id, ps.db_connection_id, e.code, e.name, c.slug as connection, e.is_production
                from pipeline_stage ps
                join environment e on e.id = ps.environment_id
                join db_connection c on c.id = ps.db_connection_id
                where ps.pipeline_id = :p order by ps.sort_order
                """).param("p", pipelineId)
                .query((rs, n) -> new Stage(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getBoolean(6)))
                .list();
        List<UUID> connections = stages.stream().map(Stage::connectionId).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("id", pipelineId);
        pipeline.put("name", found.get(0).get("name"));
        pipeline.put("project", found.get(0).get("project"));
        out.put("pipeline", pipeline);
        out.put("period", Map.of("days", days, "from", from, "to", now));

        if (connections.isEmpty()) {
            out.put("kpis", kpis(0, 0, 0, 0));
            out.put("gates", List.of());
            out.put("findings", List.of());
            out.put("breakFix", List.of());
            out.put("totalFindings", 0);
            return out;
        }

        out.put("kpis", kpis(from, connections));
        out.put("gates", stages.stream().map(s -> gate(s, from)).toList());

        long total = jdbc.sql("""
                select count(*) from policy_finding f join operation o
                  on o.id = f.operation_id and o.started_at = f.operation_started_at
                where o.db_connection_id in (:conns) and o.started_at >= :from
                """).param("conns", connections).param("from", Timestamp.from(from)).query(Long.class).single();
        out.put("totalFindings", total);
        out.put("findings", findings(stages, connections, from));
        out.put("latestBlocked", latestBlocked(stages, connections, from));
        out.put("breakFix", breakFix(stages, connections, from));
        return out;
    }

    private Map<String, Object> kpis(Instant from, List<UUID> connections) {
        var row = jdbc.sql("""
                select count(*) filter (where o.gate_result is not null) as evaluations,
                       count(*) filter (where o.gate_result = 'PASSED') as passed,
                       count(*) filter (where o.gate_result = 'BLOCKED') as blocked
                from operation o
                where o.db_connection_id in (:conns) and o.started_at >= :from
                """).param("conns", connections).param("from", Timestamp.from(from)).query().singleRow();
        long violations = jdbc.sql("""
                select count(*) from policy_finding f join operation o
                  on o.id = f.operation_id and o.started_at = f.operation_started_at
                where o.db_connection_id in (:conns) and o.started_at >= :from and f.severity >= 2
                """).param("conns", connections).param("from", Timestamp.from(from)).query(Long.class).single();
        return kpis(((Number) row.get("evaluations")).longValue(), violations,
                ((Number) row.get("blocked")).longValue(), ((Number) row.get("passed")).longValue());
    }

    private static Map<String, Object> kpis(long evaluations, long violations, long blocked, long passed) {
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("evaluations", evaluations);
        kpis.put("violations", violations);
        kpis.put("blocked", blocked);
        // null, not 0%, when nothing was evaluated: "no data" is not "everything failed".
        kpis.put("passRate", evaluations == 0 ? null : Math.round(1000.0 * passed / evaluations) / 10.0);
        return kpis;
    }

    private Map<String, Object> gate(Stage stage, Instant from) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("stageId", stage.id());
        card.put("env", stage.env());
        card.put("envName", stage.envName());
        card.put("connection", stage.connection());
        card.put("production", stage.production());

        var last = jdbc.sql("""
                select id, started_at, gate_result from operation
                where db_connection_id = :c and gate_result is not null and started_at >= :from
                order by started_at desc limit 1
                """).param("c", stage.connectionId()).param("from", Timestamp.from(from)).query().listOfRows();
        if (last.isEmpty()) {
            card.put("result", null);
            card.put("bySeverity", Map.of());
            card.put("findings", 0);
            return card;
        }
        card.put("result", last.get(0).get("gate_result"));
        card.put("evaluatedAt", ((Timestamp) last.get(0).get("started_at")).toInstant());
        card.put("operationId", last.get(0).get("id"));

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        jdbc.sql("select severity, count(*) from policy_finding where operation_id = :op group by severity order by severity desc")
                .param("op", last.get(0).get("id"))
                .query((rs, n) -> Map.entry(label(rs.getInt(1)), rs.getLong(2)))
                .list().forEach(e -> bySeverity.put(e.getKey(), e.getValue()));
        card.put("bySeverity", bySeverity);
        card.put("findings", bySeverity.values().stream().mapToLong(Long::longValue).sum());
        return card;
    }

    private List<Map<String, Object>> findings(List<Stage> stages, List<UUID> connections, Instant from) {
        Map<UUID, Stage> byConnection = new LinkedHashMap<>();
        stages.forEach(s -> byConnection.put(s.connectionId(), s));
        List<Map<String, Object>> out = new ArrayList<>();
        jdbc.sql("""
                select f.severity, pc.code, cs.cs_id, o.db_connection_id, f.result, f.message, o.id as operation_id,
                       o.started_at
                from policy_finding f
                join operation o on o.id = f.operation_id and o.started_at = f.operation_started_at
                join policy_check pc on pc.id = f.check_id
                left join changeset cs on cs.id = f.changeset_id
                where o.db_connection_id in (:conns) and o.started_at >= :from
                order by f.severity desc, o.started_at desc
                limit :limit
                """).param("conns", connections).param("from", Timestamp.from(from)).param("limit", MAX_FINDINGS)
                .query((rs, n) -> {
                    Stage stage = byConnection.get(rs.getObject("db_connection_id", UUID.class));
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("severity", rs.getInt("severity"));
                    f.put("check", rs.getString("code"));
                    f.put("changeset", rs.getString("cs_id"));
                    f.put("connection", stage.connection());
                    f.put("env", stage.env());
                    f.put("result", rs.getString("result"));
                    f.put("message", rs.getString("message"));
                    f.put("operationId", rs.getObject("operation_id", UUID.class));
                    f.put("at", rs.getTimestamp("started_at").toInstant());
                    return f;
                }).list().forEach(out::add);
        return out;
    }

    /**
     * Emergency edits: changesets labelled break-fix that were applied to one of the pipeline's
     * databases in the period. "Who approved" is not here: that needs the exception workflow
     * tied to the changeset, which a plain label does not carry.
     */
    private List<Map<String, Object>> breakFix(List<Stage> stages, List<UUID> connections, Instant from) {
        Map<UUID, Stage> byConnection = new LinkedHashMap<>();
        stages.forEach(s -> byConnection.put(s.connectionId(), s));
        return jdbc.sql("""
                select cs.cs_id, cs.author, cs.contexts, st.db_connection_id, st.deployed_at, st.last_operation_id
                  from changeset_state st join changeset cs on cs.id = st.changeset_id
                 where cs.is_break_fix and st.db_connection_id in (:conns) and st.deployed_at >= :from
                   and st.status = 'EXECUTED'
                 order by st.deployed_at desc limit 20
                """).param("conns", connections).param("from", Timestamp.from(from))
                .query((rs, n) -> {
                    Stage stage = byConnection.get(rs.getObject("db_connection_id", UUID.class));
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("changeset", rs.getString("cs_id"));
                    m.put("author", rs.getString("author"));
                    m.put("contexts", rs.getString("contexts"));
                    m.put("env", stage.env());
                    m.put("connection", stage.connection());
                    m.put("at", rs.getTimestamp("deployed_at").toInstant());
                    m.put("operationId", rs.getObject("last_operation_id", UUID.class));
                    return m;
                }).list();
    }

    /** The newest finding that stopped a deploy: the side card "blocked before production". */
    private Map<String, Object> latestBlocked(List<Stage> stages, List<UUID> connections, Instant from) {
        return findings(stages, connections, from).stream()
                .filter(f -> "BLOCKED".equals(f.get("result")))
                .max(java.util.Comparator.comparing(f -> (Instant) f.get("at")))
                .orElse(null);
    }

    private static String label(int severity) {
        return switch (severity) {
            case 0 -> "INFO";
            case 1 -> "MINOR";
            case 2 -> "MAJOR";
            case 3 -> "CRITICAL";
            default -> "BLOCKER";
        };
    }
}
