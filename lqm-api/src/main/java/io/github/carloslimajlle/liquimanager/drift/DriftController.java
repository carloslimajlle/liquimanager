package io.github.carloslimajlle.liquimanager.drift;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import tools.jackson.databind.json.JsonMapper;
import io.github.carloslimajlle.liquimanager.access.ReadScope;

/** Drift Detection (screen 09): runs, their items, the summary, accepting an item and the changelog that would fix it. */
@RestController
@RequestMapping(Api.V1)
class DriftController {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int WEEKS = 8;
    private static final int MIN_REASON = 10;

    record AcceptBody(String reason) {
    }

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final DriftService drift;
    private final AuditService audit;
    private final ReadScope read;

    DriftController(JdbcClient jdbc, WorkspaceContext workspaces, DriftService drift, AuditService audit, ReadScope read) {
        this.read = read;
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.drift = drift;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ actions

    @PostMapping("/connections/{id}/drift-runs")
    Map<String, Object> runNow(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        DriftService.RunResult result = drift.run(workspace, id, AuditService.actorOf(authentication), "ui");
        return runDetail(workspace, result.runId());
    }

    /** Declares what the database looks like right now as the clean state. */
    @PostMapping("/connections/{id}/drift-reference")
    @Transactional
    Map<String, Object> rebaseline(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                   Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        drift.takeReference(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "drift.reference", "connection", id.toString(), null,
                Map.of("takenAt", Instant.now()));
        return Map.of("connectionId", id, "referenceTakenAt", Instant.now());
    }

    @PostMapping("/drift/items/{id}/accept")
    @Transactional
    Map<String, Object> accept(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id, @RequestBody AcceptBody body) {
        UUID workspace = workspaces.resolve(ws);
        if (body == null || body.reason() == null || body.reason().trim().length() < MIN_REASON) {
            throw LqmException.invalid("a justification of at least " + MIN_REASON + " characters is required");
        }
        var item = jdbc.sql("""
                select i.id, i.object_type, i.object_name, i.change_type, i.accepted, i.drift_run_id, r.db_connection_id
                  from drift_item i join drift_run r on r.id = i.drift_run_id join db_connection c on c.id = r.db_connection_id
                 where i.id = :id and c.workspace_id = :ws
                """).param("id", id).param("ws", workspace).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("drift item"));
        String actor = AuditService.actorOf(authentication);
        jdbc.sql("update drift_item set accepted = true, accepted_by = :by, accepted_reason = :why where id = :id")
                .param("by", actor).param("why", body.reason().trim()).param("id", id).update();
        // Accepting removes it from the run's counters, so the run reads as it would have had it been accepted before.
        String column = switch ((String) item.get("change_type")) {
            case "ADDED" -> "added";
            case "REMOVED" -> "removed";
            default -> "modified";
        };
        if (!Boolean.TRUE.equals(item.get("accepted"))) {
            jdbc.sql("update drift_run set " + column + " = greatest(" + column + " - 1, 0) where id = :run")
                    .param("run", item.get("drift_run_id")).update();
            jdbc.sql("update drift_run set has_drift = (added + removed + modified > 0) where id = :run and has_drift is not null")
                    .param("run", item.get("drift_run_id")).update();
        }
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("object", item.get("object_type") + " " + item.get("object_name"));
        accepted.put("reason", body.reason().trim());
        audit.record(workspace, actor, "drift.accept", "drift-item", id.toString(), null, accepted);
        return Map.of("id", id, "accepted", true, "acceptedBy", actor);
    }

    @PostMapping(value = "/drift/runs/{id}/changelog", produces = MediaType.APPLICATION_XML_VALUE)
    ResponseEntity<String> changelog(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                     @PathVariable UUID id) {
        String xml = drift.diffChangelog(workspaces.resolve(ws), id);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"drift-" + id + ".xml\"")
                .contentType(MediaType.APPLICATION_XML).body(xml);
    }

    // ------------------------------------------------------------------- reading

    @GetMapping("/drift/runs")
    Map<String, Object> runs(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                             @RequestParam(defaultValue = "30d") String period,
                             @RequestParam(defaultValue = "all") String filter,
                             @RequestParam(required = false) String connection,
                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size,
                             Authentication authentication) {
        UUID workspace = workspaces.resolve(ws);
        ReadScope.Filter readable = read.of(workspace, authentication).connections("c.id", "visibleConnections");
        Instant from = from(period);
        int pageSize = Math.max(1, Math.min(size, 200));
        int pageIndex = Math.max(0, page);
        String where = """
                where c.workspace_id = :ws and r.started_at >= :from
                  and (cast(:conn as text) is null or c.slug = :conn)
                  and (:filter = 'all' or (:filter = 'drift' and r.has_drift) or (:filter = 'clean' and r.has_drift = false))
                """ + readable.sql();
        List<Map<String, Object>> rows = jdbc.sql("""
                select r.id, r.operation_id, r.status, r.has_drift, r.added, r.removed, r.modified, r.started_at, r.error,
                       c.id as connection_id, c.slug as connection, e.code as environment
                  from drift_run r join db_connection c on c.id = r.db_connection_id join environment e on e.id = c.environment_id
                """ + where + " order by r.started_at desc limit :limit offset :offset")
                .param("ws", workspace).param("from", Timestamp.from(from)).param("conn", blank(connection))
                .param("filter", filter).param("limit", pageSize).param("offset", (long) pageIndex * pageSize).params(readable.params())
                .query().listOfRows().stream().map(DriftController::presentRun).toList();
        long total = jdbc.sql("select count(*) from drift_run r join db_connection c on c.id = r.db_connection_id " + where)
                .param("ws", workspace).param("from", Timestamp.from(from)).param("conn", blank(connection))
                .param("filter", filter).params(readable.params()).query(Long.class).single();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", rows);
        out.put("page", pageIndex);
        out.put("size", pageSize);
        out.put("totalElements", total);
        return out;
    }

    @GetMapping("/drift/runs/{id}")
    Map<String, Object> run(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID id) {
        return runDetail(workspaces.resolve(ws), id);
    }

    @GetMapping("/drift/summary")
    Map<String, Object> summary(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                @RequestParam(defaultValue = "30d") String period,
                                @RequestParam(required = false) String connection) {
        UUID workspace = workspaces.resolve(ws);
        Instant from = from(period);

        var totals = jdbc.sql("""
                select count(*) filter (where r.status = 'SUCCESS') as checks,
                       count(*) filter (where r.has_drift) as drift,
                       count(*) filter (where r.has_drift = false) as clean,
                       coalesce(sum(r.added) filter (where r.has_drift), 0) as added,
                       coalesce(sum(r.removed) filter (where r.has_drift), 0) as removed,
                       coalesce(sum(r.modified) filter (where r.has_drift), 0) as modified
                  from drift_run r join db_connection c on c.id = r.db_connection_id
                 where c.workspace_id = :ws and r.started_at >= :from and (cast(:conn as text) is null or c.slug = :conn)
                """).param("ws", workspace).param("from", Timestamp.from(from)).param("conn", blank(connection))
                .query().singleRow();
        long checks = ((Number) totals.get("checks")).longValue();
        long driftRuns = ((Number) totals.get("drift")).longValue();
        long added = ((Number) totals.get("added")).longValue();
        long removed = ((Number) totals.get("removed")).longValue();
        long modified = ((Number) totals.get("modified")).longValue();
        long items = added + removed + modified;

        Map<String, Object> out = new LinkedHashMap<>();
        // null, not 0%, with no checks: "nothing was checked" is not "everything is clean".
        out.put("driftRate", checks == 0 ? null : Math.round(1000.0 * driftRuns / checks) / 10.0);
        out.put("driftDetected", driftRuns);
        out.put("cleanChecks", ((Number) totals.get("clean")).longValue());
        out.put("items", Map.of("total", items, "added", added, "removed", removed, "modified", modified));
        out.put("averageItems", driftRuns == 0 ? null : Math.round(10.0 * items / driftRuns) / 10.0);
        out.put("coverage", jdbc.sql("""
                select count(*) filter (where drift_enabled) as monitored, count(*) as total
                  from db_connection where workspace_id = :ws and archived_at is null
                """).param("ws", workspace).query().singleRow());
        out.put("weekly", weekly(workspace, blank(connection)));

        // "What diverged": the chosen connection, or the one with the most drift.
        String focus = blank(connection) != null ? connection : jdbc.sql("""
                select c.slug from drift_item i join drift_run r on r.id = i.drift_run_id
                  join db_connection c on c.id = r.db_connection_id
                 where c.workspace_id = :ws and r.started_at >= :from and not i.accepted
                 group by c.slug order by count(*) desc limit 1
                """).param("ws", workspace).param("from", Timestamp.from(from)).query(String.class).optional().orElse(null);
        Map<String, Object> diverged = new LinkedHashMap<>();
        diverged.put("connection", focus);
        diverged.put("byType", focus == null ? List.of() : jdbc.sql("""
                select i.object_type as type, count(*) as count from drift_item i join drift_run r on r.id = i.drift_run_id
                  join db_connection c on c.id = r.db_connection_id
                 where c.workspace_id = :ws and c.slug = :slug and r.started_at >= :from and not i.accepted
                 group by i.object_type order by count(*) desc
                """).param("ws", workspace).param("slug", focus).param("from", Timestamp.from(from)).query().listOfRows());
        diverged.put("comparedAgainst", "last clean snapshot");
        out.put("diverged", diverged);
        return out;
    }

    // ------------------------------------------------------------------- helpers

    private List<Map<String, Object>> weekly(UUID workspace, String connection) {
        LocalDate thisMonday = LocalDate.now(ZoneOffset.UTC).with(java.time.DayOfWeek.MONDAY);
        List<Map<String, Object>> weeks = new ArrayList<>();
        for (int i = WEEKS - 1; i >= 0; i--) {
            LocalDate start = thisMonday.minusWeeks(i);
            var row = jdbc.sql("""
                    select count(*) filter (where r.status = 'SUCCESS') as checks, count(*) filter (where r.has_drift) as drift,
                           coalesce(sum(r.added + r.removed + r.modified) filter (where r.has_drift), 0) as items
                      from drift_run r join db_connection c on c.id = r.db_connection_id
                     where c.workspace_id = :ws and r.started_at >= :from and r.started_at < :to
                       and (cast(:conn as text) is null or c.slug = :conn)
                    """).param("ws", workspace).param("conn", connection)
                    .param("from", Timestamp.from(start.atStartOfDay().toInstant(ZoneOffset.UTC)))
                    .param("to", Timestamp.from(start.plusWeeks(1).atStartOfDay().toInstant(ZoneOffset.UTC)))
                    .query().singleRow();
            long checks = ((Number) row.get("checks")).longValue();
            long drift = ((Number) row.get("drift")).longValue();
            Map<String, Object> week = new LinkedHashMap<>();
            week.put("week", start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            week.put("from", start);
            week.put("items", ((Number) row.get("items")).longValue());
            week.put("rate", checks == 0 ? null : Math.round(1000.0 * drift / checks) / 10.0);
            weeks.add(week);
        }
        return weeks;
    }

    private Map<String, Object> runDetail(UUID workspace, UUID id) {
        var rows = jdbc.sql("""
                select r.id, r.operation_id, r.status, r.has_drift, r.added, r.removed, r.modified, r.started_at, r.error,
                       r.reference_desc, c.id as connection_id, c.slug as connection, e.code as environment
                  from drift_run r join db_connection c on c.id = r.db_connection_id join environment e on e.id = c.environment_id
                 where r.id = :id and c.workspace_id = :ws
                """).param("id", id).param("ws", workspace).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("drift run");
        }
        Map<String, Object> out = presentRun(rows.get(0));
        out.put("reference", rows.get(0).get("reference_desc"));
        out.put("items", jdbc.sql("""
                select id, change_type, object_type, object_name, cast(details as text) as details, accepted, accepted_by, accepted_reason
                  from drift_item where drift_run_id = :id order by object_type, object_name
                """).param("id", id).query().listOfRows().stream().map(i -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", i.get("id"));
            item.put("change", i.get("change_type"));
            item.put("objectType", i.get("object_type"));
            item.put("objectName", i.get("object_name"));
            item.put("details", i.get("details") == null ? null : JSON.readTree((String) i.get("details")));
            item.put("accepted", i.get("accepted"));
            item.put("acceptedBy", i.get("accepted_by"));
            item.put("acceptedReason", i.get("accepted_reason"));
            return item;
        }).toList());
        return out;
    }

    private static Map<String, Object> presentRun(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("operationId", r.get("operation_id"));
        m.put("command", "diff");
        m.put("status", r.get("status"));
        m.put("hasDrift", r.get("has_drift"));
        m.put("added", r.get("added"));
        m.put("removed", r.get("removed"));
        m.put("modified", r.get("modified"));
        m.put("connectionId", r.get("connection_id"));
        m.put("connection", r.get("connection"));
        m.put("environment", r.get("environment"));
        m.put("startedAt", ((Timestamp) r.get("started_at")).toInstant());
        m.put("error", r.get("error"));
        return m;
    }

    private static Instant from(String period) {
        if ("all".equalsIgnoreCase(period)) {
            return Instant.EPOCH;
        }
        int days = switch (period == null ? "30d" : period) {
            case "7d" -> 7;
            case "30d" -> 30;
            case "90d" -> 90;
            default -> throw LqmException.invalid("period must be 7d, 30d, 90d or all");
        };
        return Instant.now().minus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS);
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
