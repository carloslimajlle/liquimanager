package io.github.carloslimajlle.liquimanager.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.access.ReadScope;
import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * Pipelines and their ordered stages (RF-CAD): a pipeline is the path a change walks
 * from one environment to the next, and each stage binds an environment to the
 * database that represents it there. The Deployment Matrix reads exactly this.
 */
@RestController
@RequestMapping(Api.V1 + "/pipelines")
class PipelineController {

    private static final String PIPELINE_COLUMNS = "id, name, project_id, gitlab_project_id, gitlab_trigger_ref";
    private static final String STAGE_COLUMNS =
            "id, pipeline_id, sort_order, environment_id, db_connection_id, policy_package_id, requires_approval, auto_request";

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;
    private final ReadScope read;

    PipelineController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit, ReadScope read) {
        this.read = read;
        this.audit = audit;
        this.jdbc = jdbc;
        this.workspaces = workspaces;
    }

    record PipelineRequest(String name, UUID projectId, Long gitlabProjectId, String gitlabTriggerRef) {
    }

    record StageRequest(Integer sortOrder, UUID environmentId, UUID dbConnectionId, UUID policyPackageId,
                        Boolean requiresApproval, Boolean autoRequest) {
    }

    // --------------------------------------------------------------- pipelines

    @GetMapping
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                   Authentication authentication) {
        UUID workspace = workspaces.resolve(ws);
        ReadScope.Filter visible = read.of(workspace, authentication).pipelines("id", "visiblePipelines");
        return Rows.camel(jdbc.sql("select " + PIPELINE_COLUMNS + " from pipeline where workspace_id = :ws" + visible.sql() + " order by name")
                .param("ws", workspace).params(visible.params()).query().listOfRows());
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                            @PathVariable UUID id) {
        return findPipeline(workspaces.resolve(ws), id);
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @RequestBody PipelineRequest body) {
        UUID workspace = workspaces.resolve(ws);
        Validation.required(body.name(), "name");
        UUID id = UuidV7.next();
        jdbc.sql("""
                insert into pipeline (id, workspace_id, name, project_id, gitlab_project_id, gitlab_trigger_ref)
                values (:id, :ws, :name, :project, :gitlab, :ref)
                """)
                .param("id", id).param("ws", workspace).param("name", body.name().trim())
                .param("project", body.projectId()).param("gitlab", body.gitlabProjectId())
                .param("ref", body.gitlabTriggerRef())
                .update();
        Map<String, Object> created = findPipeline(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "pipeline.create", "pipeline", id.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    Map<String, Object> update(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id, @RequestBody PipelineRequest body) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = findPipeline(workspace, id);
        Validation.required(body.name(), "name");
        jdbc.sql("""
                update pipeline set name = :name, project_id = :project, gitlab_project_id = :gitlab,
                       gitlab_trigger_ref = :ref where id = :id and workspace_id = :ws
                """)
                .param("name", body.name().trim()).param("project", body.projectId())
                .param("gitlab", body.gitlabProjectId()).param("ref", body.gitlabTriggerRef())
                .param("id", id).param("ws", workspace)
                .update();
        Map<String, Object> after = findPipeline(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "pipeline.update", "pipeline", id.toString(), before, after);
        return after;
    }

    /** Deleting a pipeline takes its stages with it; the matrix history is in operations, not here. */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = findPipeline(workspace, id);
        jdbc.sql("delete from pipeline_stage where pipeline_id = :id").param("id", id).update();
        jdbc.sql("delete from pipeline where id = :id and workspace_id = :ws")
                .param("id", id).param("ws", workspace).update();
        audit.record(workspace, AuditService.actorOf(authentication), "pipeline.delete", "pipeline", id.toString(), before, null);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------ stages

    @GetMapping("/{id}/stages")
    List<Map<String, Object>> stages(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                     @PathVariable UUID id) {
        findPipeline(workspaces.resolve(ws), id);
        return Rows.camel(jdbc.sql("select " + STAGE_COLUMNS + " from pipeline_stage where pipeline_id = :id "
                + "order by sort_order").param("id", id).query().listOfRows());
    }

    @PostMapping("/{id}/stages")
    ResponseEntity<Map<String, Object>> addStage(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @PathVariable UUID id, @RequestBody StageRequest body) {
        UUID workspace = workspaces.resolve(ws);
        findPipeline(workspace, id);
        validateStage(body);
        UUID stageId = UuidV7.next();
        jdbc.sql("""
                insert into pipeline_stage (id, pipeline_id, sort_order, environment_id, db_connection_id,
                                            policy_package_id, requires_approval, auto_request)
                values (:id, :pipeline, :ord, :env, :conn, :pkg, :approval, :auto)
                """)
                .param("id", stageId).param("pipeline", id).param("ord", body.sortOrder())
                .param("env", body.environmentId()).param("conn", body.dbConnectionId())
                .param("pkg", body.policyPackageId()).param("approval", Boolean.TRUE.equals(body.requiresApproval()))
                .param("auto", Boolean.TRUE.equals(body.autoRequest()))
                .update();
        Map<String, Object> created = findStage(id, stageId);
        audit.record(workspace, AuditService.actorOf(authentication), "pipeline-stage.create", "pipeline-stage", stageId.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}/stages/{stageId}")
    Map<String, Object> updateStage(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                    Authentication authentication, @PathVariable UUID id, @PathVariable UUID stageId,
                                    @RequestBody StageRequest body) {
        UUID workspace = workspaces.resolve(ws);
        findPipeline(workspace, id);
        Map<String, Object> before = findStage(id, stageId);
        validateStage(body);
        jdbc.sql("""
                update pipeline_stage set sort_order = :ord, environment_id = :env, db_connection_id = :conn,
                       policy_package_id = :pkg, requires_approval = :approval, auto_request = :auto
                 where id = :stage and pipeline_id = :pipeline
                """)
                .param("ord", body.sortOrder()).param("env", body.environmentId())
                .param("conn", body.dbConnectionId()).param("pkg", body.policyPackageId())
                .param("approval", Boolean.TRUE.equals(body.requiresApproval()))
                .param("auto", Boolean.TRUE.equals(body.autoRequest()))
                .param("stage", stageId).param("pipeline", id)
                .update();
        Map<String, Object> after = findStage(id, stageId);
        audit.record(workspace, AuditService.actorOf(authentication), "pipeline-stage.update", "pipeline-stage", stageId.toString(), before, after);
        return after;
    }

    @DeleteMapping("/{id}/stages/{stageId}")
    ResponseEntity<Void> deleteStage(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                     Authentication authentication, @PathVariable UUID id, @PathVariable UUID stageId) {
        UUID workspace = workspaces.resolve(ws);
        findPipeline(workspace, id);
        Map<String, Object> before = findStage(id, stageId);
        jdbc.sql("delete from pipeline_stage where id = :stage and pipeline_id = :pipeline")
                .param("stage", stageId).param("pipeline", id).update();
        audit.record(workspace, AuditService.actorOf(authentication), "pipeline-stage.delete", "pipeline-stage", stageId.toString(), before, null);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------- helpers

    private static void validateStage(StageRequest body) {
        if (body.sortOrder() == null || body.environmentId() == null || body.dbConnectionId() == null) {
            throw LqmException.invalid("sortOrder, environmentId and dbConnectionId are required");
        }
    }

    private Map<String, Object> findPipeline(UUID workspace, UUID id) {
        List<Map<String, Object>> rows = jdbc.sql("select " + PIPELINE_COLUMNS
                        + " from pipeline where id = :id and workspace_id = :ws")
                .param("id", id).param("ws", workspace).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("pipeline");
        }
        return Rows.camel(rows.get(0));
    }

    private Map<String, Object> findStage(UUID pipelineId, UUID stageId) {
        List<Map<String, Object>> rows = jdbc.sql("select " + STAGE_COLUMNS
                        + " from pipeline_stage where id = :stage and pipeline_id = :pipeline")
                .param("stage", stageId).param("pipeline", pipelineId).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("pipeline stage");
        }
        return Rows.camel(rows.get(0));
    }
}
