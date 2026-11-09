package io.github.carloslimajlle.liquimanager.matrix;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.access.PermissionService;
import io.github.carloslimajlle.liquimanager.access.ScopeResolver;
import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.access.ReadScope;

/**
 * The second person of a promotion that needs one: approves it (which fires the GitLab trigger), rejects it, or the
 * requester withdraws it.
 *
 * <p>Separation of duties (docs/09 § 4, SoD-2): whoever asked for a promotion cannot approve it, checked here on the
 * server. Approving and rejecting need {@code promote:<env>} for the stage's environment, like asking does, so a person
 * who could not have asked cannot decide either.
 */
@RestController
@RequestMapping(Api.V1 + "/promotions")
class PromotionDecisionController {

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final PermissionService permissions;
    private final AuditService audit;
    private final Promotions promotions;
    private final ScopeResolver scopes;
    private final ReadScope read;

    PromotionDecisionController(JdbcClient jdbc, WorkspaceContext workspaces, PermissionService permissions, AuditService audit,
                                Promotions promotions, ScopeResolver scopes, ReadScope read) {
        this.read = read;
        this.scopes = scopes;
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.permissions = permissions;
        this.audit = audit;
        this.promotions = promotions;
    }

    @GetMapping
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                   @RequestParam(required = false) UUID pipeline, @RequestParam(required = false) String status,
                                   Authentication authentication) {
        UUID workspace = workspaces.resolve(ws);
        ReadScope.Filter readable = read.of(workspace, authentication).pipelines("pipeline_id", "visiblePipelines");
        return jdbc.sql("""
                select id, pipeline_id, stage_id, environment, release, status, requested_by, decided_by, decided_at,
                       gitlab_pipeline, gitlab_url, error, created_at
                  from promotion
                 where workspace_id = :ws
                   and (cast(:pipeline as uuid) is null or pipeline_id = :pipeline)
                   and (cast(:status as text) is null or status = :status)
                """ + readable.sql() + " order by created_at desc limit 100")
                .param("ws", workspace).param("pipeline", pipeline).params(readable.params())
                .param("status", status == null || status.isBlank() ? null : status.trim().toUpperCase())
                .query().listOfRows().stream().map(Promotions::present).toList();
    }

    /**
     * What approving this promotion would put in the target: the changesets already executed in the stage before it and not yet in the
     * stage it is for (the same rule the deploy enforces, D-22). This is what the approver reads before deciding.
     */
    @GetMapping("/{id}/changes")
    List<Map<String, Object>> changes(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        load(workspace, id);
        return jdbc.sql("""
                select cs.id, cs.cs_id, cs.author, cs.file_path, cs.labels, cs.has_rollback, cs.is_break_fix, cl.name as changelog
                  from promotion p
                  join pipeline pl on pl.id = p.pipeline_id
                  join pipeline_stage cur on cur.id = p.stage_id
                  join pipeline_stage prev on prev.pipeline_id = cur.pipeline_id
                       and prev.sort_order = (select max(x.sort_order) from pipeline_stage x
                                               where x.pipeline_id = cur.pipeline_id and x.sort_order < cur.sort_order)
                  join changelog cl on cl.project_id = pl.project_id
                  join changeset cs on cs.changelog_id = cl.id
                  join changeset_state sprev on sprev.changeset_id = cs.id and sprev.db_connection_id = prev.db_connection_id
                       and sprev.status = 'EXECUTED'
                 where p.id = :id and p.workspace_id = :ws
                   and cs.removed_at is null and cs.seen_on_default_at is not null
                   and not exists (select 1 from changeset_state here
                                    where here.changeset_id = cs.id and here.db_connection_id = cur.db_connection_id
                                      and here.status = 'EXECUTED')
                 order by cs.first_seen_at, cs.cs_id
                """).param("id", id).param("ws", workspace).query().listOfRows().stream().map(r -> {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("id", r.get("id"));
            item.put("changesetId", r.get("cs_id"));
            item.put("author", r.get("author"));
            item.put("filePath", r.get("file_path"));
            item.put("changelog", r.get("changelog"));
            item.put("release", io.github.carloslimajlle.liquimanager.common.Labels.release((String) r.get("labels")));
            item.put("hasRollback", r.get("has_rollback"));
            item.put("isBreakFix", r.get("is_break_fix"));
            return item;
        }).toList();
    }

    @PostMapping("/{id}/approve")
    Map<String, Object> approve(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        String actor = AuditService.actorOf(authentication);
        Map<String, Object> row = load(workspace, id);
        requireRequested(row);
        requirePermission(workspace, authentication, row);
        if (actor.equalsIgnoreCase((String) row.get("requested_by"))) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-010", "SoD-2: whoever asked for a promotion cannot approve it");
        }
        Long gitlabProject = (Long) row.get("gitlab_project_id");
        String ref = (String) row.get("gitlab_trigger_ref");
        if (gitlabProject == null || ref == null || ref.isBlank()) {
            throw LqmException.invalid("the pipeline no longer has a GitLab project and ref to trigger");
        }
        if (!promotions.configured()) {
            throw LqmException.invalid("GitLab is not configured (lqm.gitlab.base-url and lqm.gitlab.trigger-token)");
        }

        // The claim: only one approval wins, and it is recorded before GitLab is called, so a second click cannot fire twice.
        int claimed = jdbc.sql("""
                update promotion set status = 'TRIGGERED', decided_by = :by, decided_at = now()
                 where id = :id and status = 'REQUESTED'
                """).param("by", actor).param("id", id).update();
        if (claimed == 0) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409", "the promotion was decided by somebody else");
        }

        Promotions.Fired fired = promotions.fire(gitlabProject, ref, (String) row.get("environment"), (UUID) row.get("stage_id"),
                (String) row.get("release"), (String) row.get("requested_by"));
        if (fired.ok()) {
            jdbc.sql("update promotion set gitlab_pipeline = :gl, gitlab_url = :url where id = :id")
                    .param("gl", fired.pipeline()).param("url", fired.webUrl()).param("id", id).update();
        } else {
            jdbc.sql("update promotion set status = 'FAILED', error = :error where id = :id")
                    .param("error", fired.error()).param("id", id).update();
        }
        Map<String, Object> after = promotions.find(workspace, id);
        audit.record(workspace, actor, "pipeline.promote.approve", "promotion", id.toString(), Promotions.present(row), after);
        if (!fired.ok()) {
            throw Promotions.gitlabRefused(fired.error());
        }
        return after;
    }

    @PostMapping("/{id}/reject")
    Map<String, Object> reject(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        String actor = AuditService.actorOf(authentication);
        Map<String, Object> row = load(workspace, id);
        requireRequested(row);
        requirePermission(workspace, authentication, row);
        return decide(workspace, actor, id, row, "REJECTED", "pipeline.promote.reject");
    }

    /** The requester takes the request back. */
    @PostMapping("/{id}/cancel")
    Map<String, Object> cancel(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        String actor = AuditService.actorOf(authentication);
        Map<String, Object> row = load(workspace, id);
        requireRequested(row);
        if (!actor.equalsIgnoreCase((String) row.get("requested_by"))) {
            throw LqmException.forbidden("only the person who asked can cancel the promotion");
        }
        return decide(workspace, actor, id, row, "CANCELED", "pipeline.promote.cancel");
    }

    private Map<String, Object> decide(UUID workspace, String actor, UUID id, Map<String, Object> row, String status, String action) {
        int updated = jdbc.sql("""
                update promotion set status = :status, decided_by = :by, decided_at = now()
                 where id = :id and status = 'REQUESTED'
                """).param("status", status).param("by", actor).param("id", id).update();
        if (updated == 0) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409", "the promotion was decided by somebody else");
        }
        Map<String, Object> after = promotions.find(workspace, id);
        audit.record(workspace, actor, action, "promotion", id.toString(), Promotions.present(row), after);
        return after;
    }

    private void requireRequested(Map<String, Object> row) {
        if (!"REQUESTED".equals(row.get("status"))) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409", "the promotion is " + row.get("status") + ", not waiting for a decision");
        }
    }

    private void requirePermission(UUID workspace, Authentication authentication, Map<String, Object> row) {
        String needed = "promote:" + ((String) row.get("environment")).toLowerCase();
        PermissionService.Identity who = PermissionService.identityOf(authentication);
        if (!permissions.allowed(workspace, who, needed, scopes.forStage((UUID) row.get("pipeline_id"), (UUID) row.get("stage_id")))) {
            throw LqmException.forbidden("missing permission " + needed);
        }
    }

    /** The promotion with what the pipeline says about its project and GitLab trigger. */
    private Map<String, Object> load(UUID workspace, UUID id) {
        return jdbc.sql("""
                select p.id, p.pipeline_id, p.stage_id, p.environment, p.release, p.status, p.requested_by, p.decided_by,
                       p.decided_at, p.gitlab_pipeline, p.gitlab_url, p.error, p.created_at,
                       pl.project_id, pl.gitlab_project_id, pl.gitlab_trigger_ref
                  from promotion p join pipeline pl on pl.id = p.pipeline_id
                 where p.id = :id and p.workspace_id = :ws
                """).param("id", id).param("ws", workspace).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("promotion"));
    }
}
