package io.github.carloslimajlle.liquimanager.matrix;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.access.PermissionService;
import io.github.carloslimajlle.liquimanager.access.ScopeResolver;
import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.Labels;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * The timeline of a pipeline and the promote button (screen 04, RF-AUT-05).
 *
 * <p>Promoting does not run anything here: it fires a GitLab pipeline trigger on the project and ref the pipeline
 * is configured with, handing it the target environment and release as variables. What that run does shows up
 * later as an ordinary operation, reported by the collector inside the job. So {@code promotion} records only that
 * somebody asked, and what GitLab answered.
 *
 * <p>The trigger token is configuration ({@code lqm.gitlab.trigger-token}), never a column: it is a credential. A stage that
 * requires approval turns the request into a {@code REQUESTED} promotion that a second person decides.
 */
@RestController
@RequestMapping(Api.V1 + "/pipelines/{id}")
class PromotionController {

    private static final int TIMELINE_LIMIT = 500;

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final PermissionService permissions;
    private final AuditService audit;
    private final Promotions promotions;
    private final ScopeResolver scopes;

    PromotionController(JdbcClient jdbc, WorkspaceContext workspaces, PermissionService permissions, AuditService audit,
                        Promotions promotions, ScopeResolver scopes) {
        this.scopes = scopes;
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.permissions = permissions;
        this.audit = audit;
        this.promotions = promotions;
    }

    record PromoteRequest(String release) {
    }

    private static final int PREVIEW_LIMIT = 50;

    /**
     * What promoting to this stage would meet, before anybody clicks: the changesets the stage before it has run and this one has not
     * ({@code ready}), those among them that were edited after the stage before ran them ({@code edited}), and the ones the changelog
     * has that neither stage has run ({@code notInPrevious}). The deploy refuses the last two (D-22), so the person learns it here and not
     * from a pipeline that stops. It answers; it fires nothing.
     */
    @GetMapping("/stages/{stageId}/preview")
    Map<String, Object> preview(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                @PathVariable UUID id, @PathVariable UUID stageId) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> pipeline = pipeline(workspace, id);
        List<Map<String, Object>> stages = jdbc.sql("""
                select cur.db_connection_id as cur_conn, ce.code as env, prev.db_connection_id as prev_conn, pe.code as previous_env
                  from pipeline_stage cur
                  join environment ce on ce.id = cur.environment_id
                  left join pipeline_stage prev on prev.pipeline_id = cur.pipeline_id
                       and prev.sort_order = (select max(x.sort_order) from pipeline_stage x
                                               where x.pipeline_id = cur.pipeline_id and x.sort_order < cur.sort_order)
                  left join environment pe on pe.id = prev.environment_id
                 where cur.id = :stage and cur.pipeline_id = :pipeline
                """).param("stage", stageId).param("pipeline", id).query().listOfRows();
        if (stages.isEmpty()) {
            throw LqmException.notFound("stage");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> stage = stages.get(0);
        out.put("env", stage.get("env"));
        if (stage.get("prev_conn") == null || pipeline.get("project_id") == null) {
            out.put("gated", false);
            return out;
        }
        out.put("gated", true);
        out.put("previousEnv", stage.get("previous_env"));

        List<Map<String, Object>> rows = jdbc.sql("""
                select cs.cs_id, cs.author, cs.file_path, cs.checksum as current_checksum, cs.removed_at,
                       cs.seen_on_default_at is not null as seen_on_default,
                       sprev.status as prev_status, sprev.applied_checksum as prev_checksum, scur.status as cur_status
                  from changelog cl
                  join changeset cs on cs.changelog_id = cl.id
                  left join changeset_state sprev on sprev.changeset_id = cs.id and sprev.db_connection_id = :prev
                  left join changeset_state scur on scur.changeset_id = cs.id and scur.db_connection_id = :cur
                 where cl.project_id = :project
                 order by cs.first_seen_at, cs.cs_id
                """).param("prev", stage.get("prev_conn")).param("cur", stage.get("cur_conn"))
                .param("project", pipeline.get("project_id")).query().listOfRows();
        List<Map<String, Object>> ready = new ArrayList<>();
        List<Map<String, Object>> edited = new ArrayList<>();
        List<Map<String, Object>> notInPrevious = new ArrayList<>();
        List<Map<String, Object>> removed = new ArrayList<>();
        long removedCount = 0;
        long readyCount = 0;
        long editedCount = 0;
        long notInPreviousCount = 0;
        for (Map<String, Object> row : rows) {
            boolean inPrev = "EXECUTED".equals(row.get("prev_status"));
            boolean inCur = "EXECUTED".equals(row.get("cur_status"));
            if (inCur) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("cs_id"));
            item.put("author", row.get("author"));
            item.put("filePath", row.get("file_path"));
            if (!Boolean.TRUE.equals(row.get("seen_on_default"))) {
                // Only in a merge request or a feature branch so far: nothing to promote, and it does not hold the deploy back.
                continue;
            }
            if (row.get("removed_at") != null) {
                // Gone from the changelog: it will not be promoted, and it must not hold the deploy back. Worth saying only when a
                // database still holds it.
                if (inPrev) {
                    removedCount++;
                    if (removed.size() < PREVIEW_LIMIT) {
                        removed.add(item);
                    }
                }
                continue;
            }
            if (inPrev) {
                readyCount++;
                if (ready.size() < PREVIEW_LIMIT) {
                    ready.add(item);
                }
                String applied = (String) row.get("prev_checksum");
                String current = (String) row.get("current_checksum");
                if (applied != null && current != null && !applied.equals(current)) {
                    editedCount++;
                    if (edited.size() < PREVIEW_LIMIT) {
                        Map<String, Object> change = new LinkedHashMap<>(item);
                        change.put("appliedChecksum", applied);
                        change.put("currentChecksum", current);
                        edited.add(change);
                    }
                }
            } else if (!"NOT_TARGETED".equals(row.get("cur_status")) && !"NOT_TARGETED".equals(row.get("prev_status"))) {
                // In the changelog, run in neither stage, and meant for both: an update here would try it, and the order gate holds it back.
                notInPreviousCount++;
                if (notInPrevious.size() < PREVIEW_LIMIT) {
                    notInPrevious.add(item);
                }
            }
        }
        out.put("ready", ready);
        out.put("readyCount", readyCount);
        out.put("edited", edited);
        out.put("editedCount", editedCount);
        out.put("notInPrevious", notInPrevious);
        out.put("notInPreviousCount", notInPreviousCount);
        out.put("removed", removed);
        out.put("removedCount", removedCount);
        return out;
    }

    // ----------------------------------------------------------------- timeline

    @GetMapping("/timeline")
    List<Map<String, Object>> timeline(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                       @PathVariable UUID id,
                                       @RequestParam(required = false) String from,
                                       @RequestParam(required = false) String to) {
        UUID workspace = workspaces.resolve(ws);
        pipeline(workspace, id);
        Instant start = from == null || from.isBlank() ? Instant.now().minus(Duration.ofDays(30)) : instant(from, false);
        Instant end = to == null || to.isBlank() ? Instant.now().plus(Duration.ofDays(1)) : instant(to, true);

        List<Map<String, Object>> stages = jdbc.sql("""
                select ps.id, ps.db_connection_id, e.code
                  from pipeline_stage ps join environment e on e.id = ps.environment_id
                 where ps.pipeline_id = :id order by ps.sort_order
                """).param("id", id).query().listOfRows();
        Map<UUID, Map<String, Object>> stageByConnection = new LinkedHashMap<>();
        for (Map<String, Object> stage : stages) {
            stageByConnection.putIfAbsent((UUID) stage.get("db_connection_id"), stage);
        }

        List<Map<String, Object>> events = new ArrayList<>();
        if (!stageByConnection.isEmpty()) {
            List<Map<String, Object>> operations = jdbc.sql("""
                    select id, started_at, executed_by, ci_pipeline_id, ci_job_url, count_executed, source, db_connection_id
                      from operation
                     where workspace_id = :ws and db_connection_id in (:conns) and type = 'database-change'
                       and status = 'SUCCESS' and count_executed > 0 and parent_operation_id is null
                       and started_at >= :from and started_at < :to
                     order by started_at desc limit :limit
                    """)
                    .param("ws", workspace).param("conns", new ArrayList<>(stageByConnection.keySet()))
                    .param("from", Timestamp.from(start)).param("to", Timestamp.from(end)).param("limit", TIMELINE_LIMIT)
                    .query().listOfRows();
            Map<UUID, String> releases = releasesOf(operations);
            for (Map<String, Object> op : operations) {
                Map<String, Object> stage = stageByConnection.get((UUID) op.get("db_connection_id"));
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("kind", "DEPLOY");
                event.put("at", ((Timestamp) op.get("started_at")).toInstant().toString());
                event.put("stageId", stage.get("id"));
                event.put("environment", stage.get("code"));
                event.put("release", releases.get((UUID) op.get("id")));
                event.put("changesets", op.get("count_executed"));
                event.put("by", op.get("executed_by"));
                event.put("ciPipelineId", op.get("ci_pipeline_id"));
                event.put("url", op.get("ci_job_url"));
                event.put("operationId", op.get("id"));
                event.put("outOfProcess", "scanner".equals(op.get("source")));
                events.add(event);
            }
        }
        jdbc.sql("""
                select stage_id, environment, release, status, requested_by, gitlab_pipeline, gitlab_url, created_at
                  from promotion where pipeline_id = :id and created_at >= :from and created_at < :to
                 order by created_at desc limit :limit
                """).param("id", id).param("from", Timestamp.from(start)).param("to", Timestamp.from(end))
                .param("limit", TIMELINE_LIMIT).query().listOfRows().forEach(p -> {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("kind", "PROMOTION");
                    event.put("at", ((Timestamp) p.get("created_at")).toInstant().toString());
                    event.put("stageId", p.get("stage_id"));
                    event.put("environment", p.get("environment"));
                    event.put("release", p.get("release"));
                    event.put("by", p.get("requested_by"));
                    event.put("status", p.get("status"));
                    event.put("ciPipelineId", p.get("gitlab_pipeline"));
                    event.put("url", p.get("gitlab_url"));
                    events.add(event);
                });
        events.sort((a, b) -> ((String) b.get("at")).compareTo((String) a.get("at")));
        return events;
    }

    /** The release an operation shipped: the first release label among the changesets it executed. */
    private Map<UUID, String> releasesOf(List<Map<String, Object>> operations) {
        Map<UUID, String> out = new LinkedHashMap<>();
        if (operations.isEmpty()) {
            return out;
        }
        List<UUID> ids = operations.stream().map(o -> (UUID) o.get("id")).toList();
        jdbc.sql("""
                select oc.operation_id, cs.labels
                  from operation_changeset oc join changeset cs on cs.id = oc.changeset_id
                 where oc.operation_id in (:ids) and oc.outcome = 'EXECUTED'
                """).param("ids", ids).query().listOfRows().forEach(r -> {
                    String release = Labels.release((String) r.get("labels"));
                    if (release != null) {
                        out.putIfAbsent((UUID) r.get("operation_id"), release);
                    }
                });
        return out;
    }

    private static Instant instant(String text, boolean endOfDay) {
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notAnInstant) {
            try {
                LocalDate day = LocalDate.parse(text);
                return (endOfDay ? day.plusDays(1) : day).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notADate) {
                throw LqmException.invalid("date must be ISO-8601, like 2026-11-01");
            }
        }
    }

    // ------------------------------------------------------------------ promote

    /**
     * Asks for a release to move to the stage's environment. A stage that does not require approval fires the GitLab
     * trigger right away (201); one that does records a {@code REQUESTED} promotion and answers 202: a second person has to
     * approve it ({@link PromotionDecisionController}, SoD-2), and only then does the trigger fire.
     */
    @PostMapping("/stages/{stageId}/promote")
    ResponseEntity<Map<String, Object>> promote(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @PathVariable UUID id, @PathVariable UUID stageId,
            @RequestBody(required = false) PromoteRequest body) {
        UUID workspace = workspaces.resolve(ws);
        PermissionService.Identity who = PermissionService.identityOf(authentication);
        String actor = AuditService.actorOf(authentication);

        Map<String, Object> pipeline = pipeline(workspace, id);
        Map<String, Object> stage = jdbc.sql("""
                select ps.id, ps.sort_order, ps.requires_approval, e.code,
                       (select min(sort_order) from pipeline_stage where pipeline_id = ps.pipeline_id) as first_order
                  from pipeline_stage ps join environment e on e.id = ps.environment_id
                 where ps.id = :stage and ps.pipeline_id = :id
                """).param("stage", stageId).param("id", id).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("stage"));
        String env = (String) stage.get("code");
        if (stage.get("sort_order").equals(stage.get("first_order"))) {
            throw LqmException.invalid("the first stage has nothing to be promoted from");
        }
        if (!permissions.allowed(workspace, who, "promote:" + env.toLowerCase(), scopes.forStage(id, stageId))) {
            throw LqmException.forbidden("missing permission promote:" + env.toLowerCase());
        }
        Long gitlabProject = (Long) pipeline.get("gitlab_project_id");
        String ref = (String) pipeline.get("gitlab_trigger_ref");
        if (gitlabProject == null || ref == null || ref.isBlank()) {
            throw LqmException.invalid("the pipeline has no GitLab project and ref to trigger");
        }
        if (!promotions.configured()) {
            throw LqmException.invalid("GitLab is not configured (lqm.gitlab.base-url and lqm.gitlab.trigger-token)");
        }
        String release = body == null || body.release() == null || body.release().isBlank() ? null : body.release().trim();

        UUID promotionId = UuidV7.next();
        if (Boolean.TRUE.equals(stage.get("requires_approval"))) {
            promotions.insertRequested(workspace, id, stageId, env, release, actor, promotionId);
            Map<String, Object> requested = promotions.find(workspace, promotionId);
            audit.record(workspace, actor, "pipeline.promote.request", "promotion", promotionId.toString(), null, requested);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(requested);
        }

        Promotions.Fired fired = promotions.fire(gitlabProject, ref, env, stageId, release, actor);
        String status = fired.ok() ? "TRIGGERED" : "FAILED";
        jdbc.sql("""
                insert into promotion (id, workspace_id, pipeline_id, stage_id, environment, release, status, requested_by,
                                       gitlab_pipeline, gitlab_url, error)
                values (:id, :ws, :pipeline, :stage, :env, :release, :status, :by, :gl, :url, :error)
                """)
                .param("id", promotionId).param("ws", workspace).param("pipeline", id).param("stage", stageId)
                .param("env", env).param("release", release).param("status", status).param("by", actor)
                .param("gl", fired.pipeline()).param("url", fired.webUrl()).param("error", fired.error()).update();

        Map<String, Object> out = promotions.find(workspace, promotionId);
        audit.record(workspace, actor, "pipeline.promote", "promotion", promotionId.toString(), null, out);
        if (!fired.ok()) {
            throw Promotions.gitlabRefused(fired.error());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    private Map<String, Object> pipeline(UUID workspace, UUID id) {
        return jdbc.sql("""
                select id, project_id, gitlab_project_id, gitlab_trigger_ref
                  from pipeline where id = :id and workspace_id = :ws
                """).param("id", id).param("ws", workspace).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("pipeline"));
    }
}
