package io.github.carloslimajlle.liquimanager.catalog;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
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
 * Environments (RF-CAD-04): an ordered, workspace-configurable list. The default is
 * DEV/TST/HML/PRD (D-15), but nothing in the code assumes exactly four.
 *
 * <p>There is no DELETE: an environment is referenced by connections and pipeline
 * stages, and removing one would orphan history. It can be reordered or renamed.
 */
@RestController
@RequestMapping(Api.V1 + "/environments")
class EnvironmentController {

    private static final String COLUMNS =
            "id, code, name, sort_order, color_token, is_production, requires_approval";

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;

    EnvironmentController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.workspaces = workspaces;
    }

    record EnvironmentRequest(String code, String name, Integer sortOrder, String colorToken,
                              Boolean isProduction, Boolean requiresApproval) {
    }

    @GetMapping
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws) {
        return Rows.camel(jdbc.sql("select " + COLUMNS + " from environment where workspace_id = :ws order by sort_order")
                .param("ws", workspaces.resolve(ws)).query().listOfRows());
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @RequestBody EnvironmentRequest body) {
        UUID workspace = workspaces.resolve(ws);
        String code = Validation.required(body.code(), "code").toUpperCase(Locale.ROOT);
        Validation.required(body.name(), "name");
        if (body.sortOrder() == null) {
            throw LqmException.invalid("sortOrder is required");
        }
        UUID id = UuidV7.next();
        jdbc.sql("""
                insert into environment (id, workspace_id, code, name, sort_order, color_token,
                                         is_production, requires_approval)
                values (:id, :ws, :code, :name, :ord, :color, :prod, :approval)
                """)
                .param("id", id).param("ws", workspace).param("code", code).param("name", body.name().trim())
                .param("ord", body.sortOrder())
                .param("color", body.colorToken() == null ? "env-" + code.toLowerCase(Locale.ROOT) : body.colorToken())
                .param("prod", Boolean.TRUE.equals(body.isProduction()))
                .param("approval", Boolean.TRUE.equals(body.requiresApproval()))
                .update();
        Map<String, Object> created = find(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "environment.create", "environment", id.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    record OrderRequest(List<UUID> ids) {
    }

    /** The pipeline order, set in one go: {@code ids} lists every environment of the workspace, first to last. */
    @PutMapping("/order")
    @Transactional
    List<Map<String, Object>> reorder(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                      Authentication authentication, @RequestBody OrderRequest body) {
        UUID workspace = workspaces.resolve(ws);
        List<UUID> existing = jdbc.sql("select id from environment where workspace_id = :ws order by sort_order")
                .param("ws", workspace).query(UUID.class).list();
        if (body.ids() == null || body.ids().size() != existing.size() || !new HashSet<>(body.ids()).equals(new HashSet<>(existing))) {
            throw LqmException.invalid("ids must list every environment of the workspace exactly once");
        }
        List<Map<String, Object>> before = list(ws);
        for (int i = 0; i < body.ids().size(); i++) {
            jdbc.sql("update environment set sort_order = :ord where id = :id and workspace_id = :ws")
                    .param("ord", i + 1).param("id", body.ids().get(i)).param("ws", workspace).update();
        }
        List<Map<String, Object>> after = list(ws);
        audit.record(workspace, AuditService.actorOf(authentication), "environment.reorder", "environment", "order",
                before.stream().map(e -> e.get("code")).toList(), after.stream().map(e -> e.get("code")).toList());
        return after;
    }

    @PutMapping("/{id}")
    Map<String, Object> update(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id, @RequestBody EnvironmentRequest body) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = find(workspace, id);
        Validation.required(body.name(), "name");
        jdbc.sql("""
                update environment set name = :name, sort_order = coalesce(:ord, sort_order),
                       color_token = coalesce(:color, color_token),
                       is_production = coalesce(:prod, is_production),
                       requires_approval = coalesce(:approval, requires_approval)
                 where id = :id and workspace_id = :ws
                """)
                .param("name", body.name().trim()).param("ord", body.sortOrder()).param("color", body.colorToken())
                .param("prod", body.isProduction()).param("approval", body.requiresApproval())
                .param("id", id).param("ws", workspace)
                .update();
        Map<String, Object> after = find(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "environment.update", "environment", id.toString(), before, after);
        return after;
    }

    private Map<String, Object> find(UUID workspace, UUID id) {
        List<Map<String, Object>> rows = jdbc.sql("select " + COLUMNS
                        + " from environment where id = :id and workspace_id = :ws")
                .param("id", id).param("ws", workspace).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("environment");
        }
        return Rows.camel(rows.get(0));
    }
}
