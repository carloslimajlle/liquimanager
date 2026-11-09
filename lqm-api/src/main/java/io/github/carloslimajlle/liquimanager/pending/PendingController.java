package io.github.carloslimajlle.liquimanager.pending;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.access.PermissionService;
import io.github.carloslimajlle.liquimanager.access.ScopeResolver;
import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;

/**
 * What waits for a decision. Two views of the same requests:
 *
 * <ul>
 *   <li>{@code /me/pending}: only what the caller could actually approve, so every screen can say so. The request of somebody else
 *       (SoD-2), not about a changeset the caller wrote (SoD-1), and for which the caller holds the permission in scope.
 *   <li>{@code /projects/{id}/approvals}: everything waiting in one project, each marked with whether the caller may decide it
 *       ({@code decidable}) and whether it is theirs ({@code mine}); the project page shows what is open, not only what is theirs.
 * </ul>
 *
 * It reads and answers; the decision stays with the approve endpoints, which apply the same rules.
 */
@RestController
@RequestMapping(Api.V1)
class PendingController {

    private static final int LIMIT = 50;

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final PermissionService permissions;
    private final ScopeResolver scopes;

    PendingController(JdbcClient jdbc, WorkspaceContext workspaces, PermissionService permissions, ScopeResolver scopes) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.permissions = permissions;
        this.scopes = scopes;
    }

    @GetMapping("/me/pending")
    Map<String, Object> pending(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication) {
        return collect(workspaces.resolve(ws), authentication, null, false);
    }

    @GetMapping("/projects/{projectId}/approvals")
    Map<String, Object> approvals(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                  Authentication authentication, @PathVariable UUID projectId) {
        UUID workspace = workspaces.resolve(ws);
        if (jdbc.sql("select count(*) from project where id = :id and workspace_id = :ws")
                .param("id", projectId).param("ws", workspace).query(Long.class).single() == 0) {
            throw LqmException.notFound("project");
        }
        return collect(workspace, authentication, projectId, true);
    }

    private Map<String, Object> collect(UUID workspace, Authentication authentication, UUID project, boolean all) {
        PermissionService.Identity who = PermissionService.identityOf(authentication);
        String actor = AuditService.actorOf(authentication);

        List<Map<String, Object>> items = new ArrayList<>();
        promotions(workspace, who, actor, project, all, items);
        rollbacks(workspace, who, actor, project, all, items);
        exceptions(workspace, who, actor, project, all, items);
        if (project == null) {
            accessRequests(workspace, who, items);
        }
        items.sort((a, b) -> ((String) b.get("at")).compareTo((String) a.get("at")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    private void promotions(UUID workspace, PermissionService.Identity who, String actor, UUID project, boolean all,
                            List<Map<String, Object>> items) {
        for (Map<String, Object> p : jdbc.sql("""
                select p.id, p.pipeline_id, p.stage_id, p.environment, p.release, p.requested_by, p.created_at, pl.name as pipeline
                  from promotion p join pipeline pl on pl.id = p.pipeline_id
                 where p.workspace_id = :ws and p.status = 'REQUESTED'
                   and (cast(:project as uuid) is null or pl.project_id = :project)
                   and (:all or lower(p.requested_by) <> lower(:me))
                 order by p.created_at desc limit :limit
                """).param("ws", workspace).param("me", actor).param("project", project).param("all", all)
                .param("limit", LIMIT).query().listOfRows()) {
            boolean mine = actor.equalsIgnoreCase((String) p.get("requested_by"));
            boolean allowed = permissions.allowed(workspace, who, "promote:" + ((String) p.get("environment")).toLowerCase(),
                    scopes.forStage((UUID) p.get("pipeline_id"), (UUID) p.get("stage_id")));
            if (!all && !allowed) {
                continue;
            }
            Map<String, Object> item = item("PROMOTION", p.get("id"), p.get("requested_by"), p.get("created_at"), mine, allowed && !mine);
            item.put("environment", p.get("environment"));
            item.put("release", p.get("release"));
            item.put("pipelineId", p.get("pipeline_id"));
            item.put("pipeline", p.get("pipeline"));
            items.add(item);
        }
    }

    private void rollbacks(UUID workspace, PermissionService.Identity who, String actor, UUID project, boolean all,
                           List<Map<String, Object>> items) {
        for (Map<String, Object> r : jdbc.sql("""
                select r.id, r.operation_id, r.db_connection_id, r.requested_by, r.created_at, cs.cs_id as changeset,
                       cs.id as changeset_uuid, cs.author, e.code as environment
                  from rollback_request r
                  join db_connection c on c.id = r.db_connection_id
                  join environment e on e.id = c.environment_id
                  left join changeset cs on cs.id = r.changeset_id
                 where r.workspace_id = :ws and r.status = 'REQUESTED'
                   and (cast(:project as uuid) is null
                        or exists (select 1 from operation o where o.id = r.operation_id and o.project_id = :project))
                   and (:all or (lower(r.requested_by) <> lower(:me) and (cs.author is null or lower(cs.author) <> lower(:me))))
                 order by r.created_at desc limit :limit
                """).param("ws", workspace).param("me", actor).param("project", project).param("all", all)
                .param("limit", LIMIT).query().listOfRows()) {
            boolean mine = actor.equalsIgnoreCase((String) r.get("requested_by"));
            boolean author = r.get("author") != null && actor.equalsIgnoreCase((String) r.get("author"));
            boolean allowed = permissions.allowed(workspace, who, "rollback:approve", scopes.forConnection((UUID) r.get("db_connection_id")));
            if (!all && !allowed) {
                continue;
            }
            Map<String, Object> item = item("ROLLBACK", r.get("id"), r.get("requested_by"), r.get("created_at"), mine, allowed && !mine && !author);
            item.put("environment", r.get("environment"));
            item.put("changeset", r.get("changeset"));
            item.put("changesetId", r.get("changeset_uuid"));
            item.put("operationId", r.get("operation_id"));
            items.add(item);
        }
    }

    private void exceptions(UUID workspace, PermissionService.Identity who, String actor, UUID project, boolean all,
                            List<Map<String, Object>> items) {
        boolean allowed = permissions.allowedSomewhere(workspace, who, "exception:approve");
        if (!all && !allowed) {
            return;
        }
        for (Map<String, Object> x : jdbc.sql("""
                select x.id, x.requested_by, x.created_at, ck.code as check_code, cs.author, cs.cs_id, cs.id as changeset_uuid
                  from policy_exception x
                  join policy_check ck on ck.id = x.check_id
                  left join changeset cs on cs.id = x.changeset_id
                 where x.workspace_id = :ws and x.status = 'REQUESTED' and x.expires_at > now()
                   and (cast(:project as uuid) is null or x.project_id = :project
                        or cs.changelog_id in (select id from changelog where project_id = :project))
                   and (:all or (lower(x.requested_by) <> lower(:me) and (cs.author is null or lower(cs.author) <> lower(:me))))
                 order by x.created_at desc limit :limit
                """).param("ws", workspace).param("me", actor).param("project", project).param("all", all)
                .param("limit", LIMIT).query().listOfRows()) {
            boolean mine = actor.equalsIgnoreCase((String) x.get("requested_by"));
            boolean author = x.get("author") != null && actor.equalsIgnoreCase((String) x.get("author"));
            Map<String, Object> item = item("EXCEPTION", x.get("id"), x.get("requested_by"), x.get("created_at"), mine, allowed && !mine && !author);
            item.put("check", x.get("check_code"));
            item.put("changeset", x.get("cs_id"));
            item.put("changesetId", x.get("changeset_uuid"));
            items.add(item);
        }
    }

    /** People waiting at the door: shown, in the bell only, to whoever administers access. Not about a project, so not in a project's list. */
    private void accessRequests(UUID workspace, PermissionService.Identity who, List<Map<String, Object>> items) {
        if (!permissions.allowedSomewhere(workspace, who, "assignment:admin")) {
            return;
        }
        // the ones whose person (or group) was granted meanwhile are not waiting anymore
        jdbc.sql("""
                update access_request r set status = 'GRANTED', resolved_at = now(), resolved_by = 'system'
                 where r.workspace_id = :ws and r.status = 'OPEN'
                   and exists (select 1 from role_binding b join principal p on p.id = b.principal_id
                                where b.workspace_id = r.workspace_id
                                  and (lower(p.external_id) = lower(r.requester)
                                       or lower(p.external_id) in (select lower(g) from jsonb_array_elements_text(r.groups::jsonb) as g)))
                """).param("ws", workspace).update();
        for (Map<String, Object> r : jdbc.sql("""
                select id, requester, requested_at from access_request where workspace_id = :ws and status = 'OPEN'
                 order by requested_at limit :limit
                """).param("ws", workspace).param("limit", LIMIT).query().listOfRows()) {
            items.add(item("ACCESS", r.get("id"), r.get("requester"), r.get("requested_at"), false, true));
        }
    }

    private static Map<String, Object> item(String kind, Object id, Object requestedBy, Object createdAt, boolean mine, boolean decidable) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("kind", kind);
        item.put("id", id);
        item.put("requestedBy", requestedBy);
        item.put("at", ((Timestamp) createdAt).toInstant().toString());
        item.put("mine", mine);
        item.put("decidable", decidable);
        return item;
    }
}
