package io.github.carloslimajlle.liquimanager.changesets;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;

/**
 * What governs a project, read from its side: the policy findings of its operations and the state of drift in its databases. The
 * screens of governance and drift show the whole workspace or one pipeline; here it is one application. Read only.
 */
@RestController
@RequestMapping(Api.V1 + "/projects/{projectId}")
class ProjectGovernanceController {

    private static final int MAX_PAGE_SIZE = 200;

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;

    ProjectGovernanceController(JdbcClient jdbc, WorkspaceContext workspaces) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
    }

    /**
     * The findings of the project's operations, newest first, with how many of each result there are. {@code result} narrows to
     * {@code BLOCKED}, {@code WARNED} or {@code PASSED}.
     */
    @GetMapping("/findings")
    Map<String, Object> findings(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                 @PathVariable UUID projectId,
                                 @RequestParam(required = false) String result,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "25") int size) {
        UUID workspace = workspaces.resolve(ws);
        requireProject(workspace, projectId);
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageIndex = Math.max(0, page);
        String wanted = result == null || result.isBlank() ? null : result.trim().toUpperCase();

        String from = """
                  from policy_finding f
                  join operation o on o.id = f.operation_id and o.started_at = f.operation_started_at
                  join policy_check pc on pc.id = f.check_id
                  join db_connection c on c.id = o.db_connection_id
                  join environment e on e.id = c.environment_id
                  left join changeset cs on cs.id = f.changeset_id
                 where o.project_id = :project and o.workspace_id = :ws
                """;
        long total = jdbc.sql("select count(*) " + from + " and (cast(:result as text) is null or f.result = :result)")
                .param("project", projectId).param("ws", workspace).param("result", wanted).query(Long.class).single();
        List<Map<String, Object>> rows = jdbc.sql("""
                select f.id, f.result, f.severity, f.message, f.object_name, f.created_at, pc.code as check_code,
                       o.id as operation_id, e.code as environment, c.slug as connection, cs.id as changeset_uuid, cs.cs_id
                """ + from + " and (cast(:result as text) is null or f.result = :result) order by f.created_at desc limit :limit offset :offset")
                .param("project", projectId).param("ws", workspace).param("result", wanted)
                .param("limit", pageSize).param("offset", (long) pageIndex * pageSize)
                .query().listOfRows().stream().map(r -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", r.get("id"));
                    item.put("check", r.get("check_code"));
                    item.put("result", r.get("result"));
                    item.put("severity", r.get("severity"));
                    item.put("message", r.get("message"));
                    item.put("objectName", r.get("object_name"));
                    item.put("changeset", r.get("cs_id"));
                    item.put("changesetId", r.get("changeset_uuid"));
                    item.put("environment", r.get("environment"));
                    item.put("connection", r.get("connection"));
                    item.put("operationId", r.get("operation_id"));
                    item.put("at", ((Timestamp) r.get("created_at")).toInstant());
                    return item;
                }).toList();

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("BLOCKED", 0L);
        counts.put("WARNED", 0L);
        counts.put("PASSED", 0L);
        for (Map<String, Object> r : jdbc.sql("select f.result, count(*) as n " + from + " group by f.result")
                .param("project", projectId).param("ws", workspace).query().listOfRows()) {
            counts.put((String) r.get("result"), ((Number) r.get("n")).longValue());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", rows);
        out.put("page", pageIndex);
        out.put("size", pageSize);
        out.put("totalElements", total);
        out.put("counts", counts);
        return out;
    }

    /**
     * One entry per database of the project, with its latest drift check. A database never checked is listed too, with no run: "never
     * checked" is not "clean". The databases are those linked to the project and those its operations ran on.
     */
    @GetMapping("/drift")
    List<Map<String, Object>> drift(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                    @PathVariable UUID projectId) {
        UUID workspace = workspaces.resolve(ws);
        requireProject(workspace, projectId);
        return jdbc.sql("""
                select c.id, c.slug, e.code as environment, e.sort_order,
                       r.id as run_id, r.status, r.has_drift, r.added, r.removed, r.modified, r.started_at, r.error
                  from db_connection c
                  join environment e on e.id = c.environment_id
                  left join lateral (select * from drift_run x where x.db_connection_id = c.id order by x.started_at desc limit 1) r on true
                 where c.workspace_id = :ws and c.archived_at is null
                   and (c.id in (select db_connection_id from project_connection where project_id = :project)
                        or c.id in (select o.db_connection_id from operation o where o.project_id = :project and o.workspace_id = :ws))
                 order by e.sort_order, c.slug
                """).param("ws", workspace).param("project", projectId).query().listOfRows().stream().map(r -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("connectionId", r.get("id"));
            item.put("connection", r.get("slug"));
            item.put("environment", r.get("environment"));
            item.put("runId", r.get("run_id"));
            item.put("status", r.get("status"));
            item.put("hasDrift", r.get("has_drift"));
            item.put("added", r.get("added"));
            item.put("removed", r.get("removed"));
            item.put("modified", r.get("modified"));
            item.put("error", r.get("error"));
            item.put("checkedAt", r.get("started_at") == null ? null : ((Timestamp) r.get("started_at")).toInstant());
            return item;
        }).toList();
    }

    private void requireProject(UUID workspace, UUID projectId) {
        if (jdbc.sql("select count(*) from project where id = :id and workspace_id = :ws")
                .param("id", projectId).param("ws", workspace).query(Long.class).single() == 0) {
            throw LqmException.notFound("project");
        }
    }
}
