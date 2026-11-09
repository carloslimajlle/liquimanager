package io.github.carloslimajlle.liquimanager.catalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.access.ReadScope;
import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * Projects (RF-CAD-02): a service or system that owns one or more changelogs.
 *
 * <p>Authentication only for now. Permission checks (catalog:edit) arrive with RBAC in
 * E11 — until then any authenticated user can edit the catalogue.
 */
@RestController
@RequestMapping(Api.V1 + "/projects")
class ProjectController {

    private static final String COLUMNS =
            "id, team_id, slug, name, repo_url, default_branch, description, archived_at";

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;
    private final ReadScope read;

    ProjectController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit, ReadScope read) {
        this.read = read;
        this.audit = audit;
        this.jdbc = jdbc;
        this.workspaces = workspaces;
    }

    record ProjectRequest(String slug, String name, UUID teamId, String repoUrl, String defaultBranch,
                          String description) {
    }

    @GetMapping
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                   @RequestParam(required = false) String q, Authentication authentication) {
        UUID workspace = workspaces.resolve(ws);
        String like = q == null || q.isBlank() ? "%" : "%" + q.toLowerCase() + "%";
        ReadScope.Filter visible = read.of(workspace, authentication).projects("id", "visibleProjects");
        return Rows.camel(jdbc.sql("select " + COLUMNS + """
                  from project
                 where workspace_id = :ws and archived_at is null
                   and (lower(slug) like :q or lower(name) like :q)
                """ + visible.sql() + " order by slug").param("ws", workspace).param("q", like).params(visible.params())
                .query().listOfRows());
    }

    /**
     * The projects with what the list screen needs in one call: changelogs, connections (linked, or where it has run), the collector it
     * reports through, and its last operation; plus the four KPIs of the screen. Limited to the projects the caller may read.
     */
    @GetMapping("/overview")
    Map<String, Object> overview(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, Authentication authentication) {
        UUID workspace = workspaces.resolve(ws);
        ReadScope.Filter visible = read.of(workspace, authentication).projects("p.id", "visibleProjects");
        List<Map<String, Object>> rows = Rows.camel(jdbc.sql("""
                select p.id, p.slug, p.name, p.team_id, t.name as team, p.repo_url, p.default_branch,
                       (select count(*) from changelog cl where cl.project_id = p.id) as changelogs,
                       (select count(*) from changelog cl where cl.project_id = p.id and cl.policy_package_id is not null) as changelogs_with_package,
                       (select count(*) from (select pc.db_connection_id from project_connection pc where pc.project_id = p.id
                                              union select o.db_connection_id from operation o where o.project_id = p.id) x
                          join db_connection c on c.id = x.db_connection_id and c.archived_at is null) as connections,
                       (select o.source from operation o where o.project_id = p.id and o.source in ('starter', 'pipeline', 'cli')
                         order by o.started_at desc limit 1) as collector,
                       lo.id as last_operation_id, lo.status as last_operation_status, lo.started_at as last_operation_at
                  from project p
                  left join team t on t.id = p.team_id
                  left join lateral (select o.id, o.status, o.started_at from operation o
                                      where o.project_id = p.id and o.type <> 'read-only' order by o.started_at desc limit 1) lo on true
                 where p.workspace_id = :ws and p.archived_at is null""" + visible.sql() + " order by p.slug")
                .param("ws", workspace).params(visible.params()).query().listOfRows());

        boolean defaultPackage = jdbc.sql("select count(*) from policy_package where workspace_id = :ws and name = 'default'")
                .param("ws", workspace).query(Long.class).single() > 0;
        long changelogs = 0;
        long withCollector = 0;
        long underPolicy = 0;
        for (Map<String, Object> row : rows) {
            changelogs += ((Number) row.get("changelogs")).longValue();
            withCollector += row.get("collector") != null ? 1 : 0;
            // Under policy: the default package covers every changelog that has none of its own.
            underPolicy += defaultPackage || ((Number) row.get("changelogsWithPackage")).longValue() > 0 ? 1 : 0;
        }
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("projects", rows.size());
        kpis.put("changelogs", changelogs);
        kpis.put("withCollector", withCollector);
        kpis.put("underPolicy", underPolicy);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpis", kpis);
        out.put("projects", rows);
        return out;
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                            @PathVariable UUID id) {
        return find(workspaces.resolve(ws), id);
    }

    /** What the project's header shows: the last 30 days of its operations, and how much is hanging off it. */
    @GetMapping("/{id}/summary")
    Map<String, Object> summary(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        find(workspace, id);
        Map<String, Object> out = new LinkedHashMap<>(Rows.camel(jdbc.sql("""
                select count(*) filter (where status in ('SUCCESS', 'FAILURE', 'BLOCKED')) as finished,
                       count(*) filter (where status = 'SUCCESS') as succeeded,
                       count(*) filter (where status = 'FAILURE') as failed,
                       count(*) filter (where status = 'BLOCKED') as blocked,
                       max(started_at) as last_at
                  from operation
                 where workspace_id = :ws and project_id = :id and started_at >= now() - interval '30 days'
                   and type <> 'read-only'
                """).param("ws", workspace).param("id", id).query().singleRow()));
        long finished = ((Number) out.get("finished")).longValue();
        out.put("successRate", finished == 0 ? null : Math.round(1000.0 * ((Number) out.get("succeeded")).longValue() / finished) / 10.0);
        // Linked to the project, or where it has run: the same figure the list screen shows.
        out.put("connections", count("select count(*) from (select pc.db_connection_id from project_connection pc where pc.project_id = :id "
                + "union select o.db_connection_id from operation o where o.project_id = :id) x "
                + "join db_connection c on c.id = x.db_connection_id and c.archived_at is null", id));
        out.put("changelogs", count("select count(*) from changelog where project_id = :id", id));
        out.put("pipelines", count("select count(*) from pipeline where project_id = :id", id));
        // Open drift: the latest run of each linked connection found some and nobody accepted it all.
        out.put("openDrift", count("""
                select count(*) from project_connection pc
                  join lateral (select r.id, r.has_drift from drift_run r where r.db_connection_id = pc.db_connection_id
                                   and r.status = 'SUCCESS' order by r.started_at desc limit 1) last on true
                 where pc.project_id = :id and coalesce(last.has_drift, false)
                   and exists (select 1 from drift_item i where i.drift_run_id = last.id and not coalesce(i.accepted, false))
                """, id));
        return out;
    }

    /** The connections this project applies to (RF-CAD-02), with where each one lives. */
    @GetMapping("/{id}/connections")
    List<Map<String, Object>> connections(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                          @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        find(workspace, id);
        return Rows.camel(jdbc.sql("""
                select c.id, c.slug, c.display_name, c.engine, e.code as environment_code, e.sort_order as environment_order
                  from project_connection pc
                  join db_connection c on c.id = pc.db_connection_id and c.archived_at is null
                  join environment e on e.id = c.environment_id
                 where pc.project_id = :id
                 order by e.sort_order, c.slug
                """).param("id", id).query().listOfRows());
    }

    record ConnectionsRequest(List<UUID> connectionIds) {
    }

    /** Replaces the set of connections of the project. Audited with the slugs before and after. */
    @PutMapping("/{id}/connections")
    @Transactional
    List<Map<String, Object>> linkConnections(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                              Authentication authentication, @PathVariable UUID id,
                                              @RequestBody ConnectionsRequest body) {
        UUID workspace = workspaces.resolve(ws);
        find(workspace, id);
        List<UUID> wanted = body.connectionIds() == null ? List.of() : body.connectionIds().stream().distinct().toList();
        if (!wanted.isEmpty()) {
            long known = jdbc.sql("select count(*) from db_connection where workspace_id = :ws and archived_at is null and id in (:ids)")
                    .param("ws", workspace).param("ids", wanted).query(Long.class).single();
            if (known != wanted.size()) {
                throw LqmException.invalid("connectionIds has a connection that does not exist in this workspace");
            }
        }
        List<Object> before = connections(ws, id).stream().map(c -> c.get("slug")).toList();
        jdbc.sql("delete from project_connection where project_id = :id").param("id", id).update();
        for (UUID connection : wanted) {
            jdbc.sql("insert into project_connection (project_id, db_connection_id) values (:p, :c)")
                    .param("p", id).param("c", connection).update();
        }
        List<Map<String, Object>> after = connections(ws, id);
        audit.record(workspace, AuditService.actorOf(authentication), "project.connections", "project", id.toString(),
                Map.of("connections", before), Map.of("connections", after.stream().map(c -> c.get("slug")).toList()));
        return after;
    }

    private long count(String sql, UUID id) {
        return jdbc.sql(sql).param("id", id).query(Long.class).single();
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @RequestBody ProjectRequest body) {
        UUID workspace = workspaces.resolve(ws);
        Validation.slug(body.slug(), "slug");
        Validation.required(body.name(), "name");
        UUID id = UuidV7.next();
        jdbc.sql("""
                insert into project (id, workspace_id, team_id, slug, name, repo_url, default_branch, description)
                values (:id, :ws, :team, :slug, :name, :repo, coalesce(:branch, 'main'), :description)
                """)
                .param("id", id).param("ws", workspace).param("team", body.teamId())
                .param("slug", body.slug()).param("name", body.name().trim())
                .param("repo", body.repoUrl()).param("branch", body.defaultBranch())
                .param("description", body.description())
                .update();
        Map<String, Object> created = find(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "project.create", "project", id.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    Map<String, Object> update(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id, @RequestBody ProjectRequest body) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = find(workspace, id);
        Validation.slug(body.slug(), "slug");
        Validation.required(body.name(), "name");
        jdbc.sql("""
                update project set team_id = :team, slug = :slug, name = :name, repo_url = :repo,
                       default_branch = coalesce(:branch, default_branch), description = :description
                 where id = :id and workspace_id = :ws
                """)
                .param("team", body.teamId()).param("slug", body.slug()).param("name", body.name().trim())
                .param("repo", body.repoUrl()).param("branch", body.defaultBranch())
                .param("description", body.description()).param("id", id).param("ws", workspace)
                .update();
        Map<String, Object> after = find(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "project.update", "project", id.toString(), before, after);
        return after;
    }

    /** RF-CAD-02 CA2: archived, never deleted — the history stays. */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> archive(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                 Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = find(workspace, id);
        jdbc.sql("update project set archived_at = now() where id = :id and workspace_id = :ws")
                .param("id", id).param("ws", workspace).update();
        audit.record(workspace, AuditService.actorOf(authentication), "project.archive", "project", id.toString(), before, null);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> find(UUID workspace, UUID id) {
        List<Map<String, Object>> rows = jdbc.sql("select " + COLUMNS
                        + " from project where id = :id and workspace_id = :ws")
                .param("id", id).param("ws", workspace).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("project");
        }
        return Rows.camel(rows.get(0));
    }
}
