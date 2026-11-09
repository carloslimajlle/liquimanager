package io.github.carloslimajlle.liquimanager.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * Changelogs of a project (RF-CAD-03). One whose first sight was an ingestion arrives
 * {@code verified = false}; an admin confirms it here with a PUT that sets
 * {@code verified = true}.
 */
@RestController
@RequestMapping(Api.V1 + "/projects/{projectId}/changelogs")
class ChangelogController {

    private static final String COLUMNS = "id, project_id, name, path, format, policy_package_id, verified";

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;

    ChangelogController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.workspaces = workspaces;
    }

    record ChangelogRequest(String name, String path, String format, UUID policyPackageId, Boolean verified) {
    }

    @GetMapping
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                   @PathVariable UUID projectId) {
        requireProject(workspaces.resolve(ws), projectId);
        return Rows.camel(jdbc.sql("select " + COLUMNS + " from changelog where project_id = :p order by path")
                .param("p", projectId).query().listOfRows());
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @PathVariable UUID projectId, @RequestBody ChangelogRequest body) {
        UUID workspace = workspaces.resolve(ws);
        requireProject(workspace, projectId);
        Validation.required(body.name(), "name");
        Validation.required(body.path(), "path");
        Validation.oneOf(body.format(), "format", "XML", "YAML", "JSON", "SQL");
        UUID id = UuidV7.next();
        jdbc.sql("""
                insert into changelog (id, project_id, name, path, format, policy_package_id, verified)
                values (:id, :p, :name, :path, :format, :pkg, :verified)
                """)
                .param("id", id).param("p", projectId).param("name", body.name().trim())
                .param("path", body.path().trim()).param("format", body.format())
                .param("pkg", body.policyPackageId()).param("verified", body.verified() == null || body.verified())
                .update();
        Map<String, Object> created = find(projectId, id);
        audit.record(workspace, AuditService.actorOf(authentication), "changelog.create", "changelog", id.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    Map<String, Object> update(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID projectId, @PathVariable UUID id,
                               @RequestBody ChangelogRequest body) {
        UUID workspace = workspaces.resolve(ws);
        requireProject(workspace, projectId);
        Map<String, Object> before = find(projectId, id);
        Validation.required(body.name(), "name");
        Validation.required(body.path(), "path");
        Validation.oneOf(body.format(), "format", "XML", "YAML", "JSON", "SQL");
        jdbc.sql("""
                update changelog set name = :name, path = :path, format = :format,
                       policy_package_id = :pkg, verified = coalesce(:verified, verified)
                 where id = :id and project_id = :p
                """)
                .param("name", body.name().trim()).param("path", body.path().trim()).param("format", body.format())
                .param("pkg", body.policyPackageId()).param("verified", body.verified())
                .param("id", id).param("p", projectId)
                .update();
        Map<String, Object> after = find(projectId, id);
        audit.record(workspace, AuditService.actorOf(authentication), "changelog.update", "changelog", id.toString(), before, after);
        return after;
    }

    private void requireProject(UUID workspace, UUID projectId) {
        boolean exists = jdbc.sql("select count(*) from project where id = :id and workspace_id = :ws")
                .param("id", projectId).param("ws", workspace).query(Long.class).single() > 0;
        if (!exists) {
            throw LqmException.notFound("project");
        }
    }

    private Map<String, Object> find(UUID projectId, UUID id) {
        List<Map<String, Object>> rows = jdbc.sql("select " + COLUMNS
                        + " from changelog where id = :id and project_id = :p")
                .param("id", id).param("p", projectId).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("changelog");
        }
        return Rows.camel(rows.get(0));
    }
}
