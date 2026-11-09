package io.github.carloslimajlle.liquimanager.catalog;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
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

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * Teams that own projects. A team still referenced by a project cannot be deleted:
 * the foreign key answers 409 (LQM-409) instead of silently orphaning the project.
 */
@RestController
@RequestMapping(Api.V1 + "/teams")
class TeamController {

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;

    TeamController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.workspaces = workspaces;
    }

    record TeamRequest(String name) {
    }

    @GetMapping
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws) {
        return Rows.camel(jdbc.sql("select id, name from team where workspace_id = :ws order by name")
                .param("ws", workspaces.resolve(ws)).query().listOfRows());
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @RequestBody TeamRequest body) {
        UUID workspace = workspaces.resolve(ws);
        Validation.required(body.name(), "name");
        UUID id = UuidV7.next();
        jdbc.sql("insert into team (id, workspace_id, name) values (:id, :ws, :name)")
                .param("id", id).param("ws", workspace).param("name", body.name().trim()).update();
        Map<String, Object> created = find(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "team.create", "team", id.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    Map<String, Object> rename(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id, @RequestBody TeamRequest body) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = find(workspace, id);
        Validation.required(body.name(), "name");
        jdbc.sql("update team set name = :name where id = :id and workspace_id = :ws")
                .param("name", body.name().trim()).param("id", id).param("ws", workspace).update();
        Map<String, Object> after = find(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "team.update", "team", id.toString(), before, after);
        return after;
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = find(workspace, id);
        jdbc.sql("delete from team where id = :id and workspace_id = :ws")
                .param("id", id).param("ws", workspace).update();
        audit.record(workspace, AuditService.actorOf(authentication), "team.delete", "team", id.toString(), before, null);
        return ResponseEntity.noContent().build();
    }

    private Map<String, Object> find(UUID workspace, UUID id) {
        List<Map<String, Object>> rows = jdbc.sql("select id, name from team where id = :id and workspace_id = :ws")
                .param("id", id).param("ws", workspace).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("team");
        }
        return Rows.camel(rows.get(0));
    }
}
