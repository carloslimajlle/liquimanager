package io.github.carloslimajlle.liquimanager.access;

import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * Who can do what (screen 08): principals, role templates and the bindings that tie them together,
 * plus {@code /me}.
 *
 * <p>The separation-of-duties rules that apply to a GRANT are checked here, on the server (docs/09 § 4):
 * <ul>
 *   <li>SoD-3: a binding cannot let someone both deploy to production and approve exceptions.
 *   <li>SoD-4: a grant in a production scope needs an approver and an expiry of at most 90 days
 *       (break-glass: 24 hours).
 *   <li>SoD-5: every grant and revocation is audited with before and after.
 * </ul>
 */
@RestController
@RequestMapping(Api.V1)
class AccessController {

    private static final Set<String> SCOPES = Set.of("WORKSPACE", "PROJECT", "CONNECTION", "PIPELINE", "ENVIRONMENT");
    private static final Duration MAX_PRODUCTION_GRANT = Duration.ofDays(90);
    private static final Duration MAX_BREAK_GLASS = Duration.ofHours(24);

    record BindingBody(UUID principalId, String roleTemplate, List<String> permissions, List<String> deniedPermissions,
                       String scopeType, UUID scopeId, String approver, Instant expiresAt) {
    }

    record PrincipalBody(String type, String externalId, String displayName, String email) {
    }

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;
    private final PermissionService permissions;

    AccessController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit, PermissionService permissions) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.audit = audit;
        this.permissions = permissions;
    }

    // --------------------------------------------------------------------- me

    @GetMapping("/me")
    Map<String, Object> me(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                           Authentication authentication) {
        PermissionService.Identity who = PermissionService.identityOf(authentication);
        UUID workspace = workspaces.resolve(ws);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", who.name());
        out.put("email", who.email());
        out.put("groups", who.groups());
        out.put("devMode", who.open());
        out.put("breakGlass", who.breakGlass());
        List<String> held = who.open() || permissions.isBootstrapAdmin(who) ? List.of("*:*")
                : permissions.grantsOf(workspace, who).stream().flatMap(g -> g.permissions().stream()).distinct().sorted().toList();
        out.put("permissions", held);
        out.put("bindings", who.open() ? 0 : permissions.grantsOf(workspace, who).size());
        out.put("workspaces", jdbc.sql("select slug, name from workspace order by name").query().listOfRows());
        return out;
    }

    // ------------------------------------------------------------- templates

    @GetMapping("/role-templates")
    List<Map<String, Object>> templates() {
        return jdbc.sql("select code, name, permissions from role_template order by name").query((rs, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", rs.getString("code"));
            m.put("name", rs.getString("name"));
            m.put("permissions", strings(rs.getArray("permissions")));
            return m;
        }).list();
    }

    // ------------------------------------------------------------ principals

    @GetMapping("/principals")
    List<Map<String, Object>> principals(@RequestParam(required = false) String q) {
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
        return jdbc.sql("""
                select id, type, external_id, display_name, email from principal
                 where lower(display_name) like :q or lower(external_id) like :q or lower(coalesce(email, '')) like :q
                 order by display_name limit 100
                """).param("q", like).query().listOfRows().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("type", r.get("type"));
            m.put("externalId", r.get("external_id"));
            m.put("displayName", r.get("display_name"));
            m.put("email", r.get("email"));
            return m;
        }).toList();
    }

    /** Until the IdP sync exists (D-03), principals are registered by hand. */
    @PostMapping("/principals")
    ResponseEntity<Map<String, Object>> createPrincipal(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @RequestBody PrincipalBody body) {
        UUID workspace = workspaces.resolve(ws);
        String type = body.type() == null ? "" : body.type().toUpperCase(Locale.ROOT);
        if (!type.equals("USER") && !type.equals("GROUP")) {
            throw LqmException.invalid("type must be USER or GROUP");
        }
        if (body.externalId() == null || body.externalId().isBlank() || body.displayName() == null || body.displayName().isBlank()) {
            throw LqmException.invalid("externalId and displayName are required");
        }
        UUID id = UuidV7.next();
        jdbc.sql("insert into principal (id, type, external_id, display_name, email) values (:id, :type, :ext, :name, :email)")
                .param("id", id).param("type", type).param("ext", body.externalId().trim())
                .param("name", body.displayName().trim()).param("email", body.email()).update();
        Map<String, Object> created = new LinkedHashMap<>();
        created.put("id", id);
        created.put("type", type);
        created.put("externalId", body.externalId().trim());
        created.put("displayName", body.displayName().trim());
        created.put("email", body.email());
        audit.record(workspace, AuditService.actorOf(authentication), "access.principal.create", "principal",
                id.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // -------------------------------------------------------------- bindings

    @GetMapping("/access/bindings")
    Map<String, Object> bindings(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                 @RequestParam(required = false) String scopeType,
                                 @RequestParam(required = false) UUID scopeId) {
        UUID workspace = workspaces.resolve(ws);
        List<Map<String, Object>> rows = jdbc.sql("""
                select b.id, b.scope_type, b.scope_id, b.approver, b.expires_at, b.created_at,
                       b.extra_permissions, b.denied_permissions,
                       p.id as principal_id, p.type, p.display_name, p.external_id,
                       rt.code as template_code, rt.name as template_name, rt.permissions as template_permissions
                  from role_binding b
                  join principal p on p.id = b.principal_id
                  left join role_template rt on rt.id = b.role_template_id
                 where b.workspace_id = :ws
                   and (cast(:scopeType as text) is null or b.scope_type = :scopeType)
                   and (cast(:scopeId as uuid) is null or b.scope_id = :scopeId)
                 order by b.created_at desc
                """).param("ws", workspace).param("scopeType", scopeType).param("scopeId", scopeId)
                .query((rs, n) -> binding(rs)).list();

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("groups", jdbc.sql("""
                select count(distinct p.id) from role_binding b join principal p on p.id = b.principal_id
                 where b.workspace_id = :ws and p.type = 'GROUP'
                """).param("ws", workspace).query(Long.class).single());
        summary.put("members", jdbc.sql("""
                select count(distinct p.id) from role_binding b join principal p on p.id = b.principal_id
                 where b.workspace_id = :ws and p.type = 'USER'
                """).param("ws", workspace).query(Long.class).single());
        summary.put("openExceptions", jdbc.sql("""
                select count(*) from policy_exception where workspace_id = :ws and status = 'REQUESTED' and expires_at > now()
                """).param("ws", workspace).query(Long.class).single());
        summary.put("auditEvents", jdbc.sql("""
                select count(*) from audit_event where workspace_id = :ws and at > now() - interval '24 hours'
                """).param("ws", workspace).query(Long.class).single());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bindings", rows);
        out.put("summary", summary);
        return out;
    }

    @PostMapping("/access/bindings")
    @Transactional
    ResponseEntity<Map<String, Object>> grant(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @RequestBody BindingBody body) {
        UUID workspace = workspaces.resolve(ws);
        String scopeType = body.scopeType() == null ? "WORKSPACE" : body.scopeType().toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(scopeType)) {
            throw LqmException.invalid("scopeType must be one of " + String.join(", ", SCOPES));
        }
        if (!scopeType.equals("WORKSPACE") && body.scopeId() == null) {
            throw LqmException.invalid("scopeId is required for a " + scopeType + " scope");
        }
        long principal = jdbc.sql("select count(*) from principal where id = :id").param("id", body.principalId())
                .query(Long.class).single();
        if (principal == 0) {
            throw LqmException.invalid("unknown principal");
        }

        List<String> extra = body.permissions() == null ? List.of() : body.permissions();
        List<String> denied = body.deniedPermissions() == null ? List.of() : body.deniedPermissions();
        for (String permission : concat(extra, denied)) {
            if (!Permissions.isWellFormed(permission)) {
                throw LqmException.invalid("not a permission: " + permission);
            }
        }

        UUID templateId = null;
        List<String> templatePermissions = List.of();
        String templateCode = body.roleTemplate() == null ? null : body.roleTemplate().toUpperCase(Locale.ROOT);
        if (templateCode != null && !templateCode.isBlank()) {
            var template = jdbc.sql("select id, permissions from role_template where code = :code").param("code", templateCode)
                    .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), strings(rs.getArray("permissions"))))
                    .optional().orElseThrow(() -> LqmException.invalid("unknown role template: " + body.roleTemplate()));
            templateId = template.getKey();
            templatePermissions = template.getValue();
        }
        if (templateId == null && extra.isEmpty() && denied.isEmpty()) {
            throw LqmException.invalid("a binding needs a role template, permissions or denied permissions");
        }

        // Does the scope exist, in this workspace?
        boolean production = scopeIsProduction(workspace, scopeType, body.scopeId());

        List<String> effective = Permissions.effective(templatePermissions, extra, denied);
        // SoD-3: this binding would let one person deploy to production and also approve its exceptions.
        // Only an ENVIRONMENT scope that is not production is out of reach of that.
        boolean reachesProduction = production || !scopeType.equals("ENVIRONMENT");
        if (reachesProduction && Permissions.violatesSod3(effective)) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-010",
                    "SoD-3: a binding cannot grant both deploy:prd and exception:approve; deny one of them");
        }

        // SoD-4: production access is approved and temporary.
        boolean breakGlass = "BREAKGLASS".equals(templateCode);
        if (production || breakGlass) {
            if (body.approver() == null || body.approver().isBlank()) {
                throw new LqmException(HttpStatus.CONFLICT, "LQM-010", "SoD-4: a production grant needs an approver");
            }
            Duration limit = breakGlass ? MAX_BREAK_GLASS : MAX_PRODUCTION_GRANT;
            if (body.expiresAt() == null || body.expiresAt().isAfter(Instant.now().plus(limit))) {
                throw new LqmException(HttpStatus.CONFLICT, "LQM-010",
                        "SoD-4: a production grant needs an expiry of at most " + (breakGlass ? "24 hours" : "90 days"));
            }
        }
        if (body.expiresAt() != null && !body.expiresAt().isAfter(Instant.now())) {
            throw LqmException.invalid("expiresAt must be in the future");
        }

        UUID id = UuidV7.next();
        jdbc.sql("""
                insert into role_binding (id, workspace_id, principal_id, role_template_id, extra_permissions,
                    denied_permissions, scope_type, scope_id, approver, expires_at)
                values (:id, :ws, :principal, :template, cast(:extra as text[]), cast(:denied as text[]),
                        :scopeType, :scopeId, :approver, :expires)
                """)
                .param("id", id).param("ws", workspace).param("principal", body.principalId()).param("template", templateId)
                .param("extra", pgArray(extra)).param("denied", pgArray(denied))
                .param("scopeType", scopeType).param("scopeId", body.scopeId()).param("approver", body.approver())
                .param("expires", body.expiresAt() == null ? null : OffsetDateTime.ofInstant(body.expiresAt(), ZoneOffset.UTC))
                .update();

        Map<String, Object> created = bindingById(workspace, id);
        audit.record(workspace, AuditService.actorOf(authentication), "access.grant", "role-binding", id.toString(),
                null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @DeleteMapping("/access/bindings/{id}")
    @Transactional
    ResponseEntity<Void> revoke(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = bindingById(workspace, id);
        jdbc.sql("delete from role_binding where id = :id and workspace_id = :ws").param("id", id).param("ws", workspace).update();
        audit.record(workspace, AuditService.actorOf(authentication), "access.revoke", "role-binding", id.toString(),
                before, null);
        return ResponseEntity.noContent().build();
    }

    // ----------------------------------------------------------------- helpers

    private boolean scopeIsProduction(UUID workspace, String scopeType, UUID scopeId) {
        if (scopeType.equals("WORKSPACE")) {
            return false;
        }
        String sql = switch (scopeType) {
            case "PROJECT" -> "select false from project where id = :id and workspace_id = :ws";
            case "CONNECTION" -> "select e.is_production from db_connection c join environment e on e.id = c.environment_id "
                    + "where c.id = :id and c.workspace_id = :ws";
            case "PIPELINE" -> "select false from pipeline where id = :id and workspace_id = :ws";
            default -> "select is_production from environment where id = :id and workspace_id = :ws";
        };
        return jdbc.sql(sql).param("id", scopeId).param("ws", workspace).query(Boolean.class).optional()
                .orElseThrow(() -> LqmException.invalid("the scope does not exist in this workspace"));
    }

    private Map<String, Object> bindingById(UUID workspace, UUID id) {
        return jdbc.sql("""
                select b.id, b.scope_type, b.scope_id, b.approver, b.expires_at, b.created_at,
                       b.extra_permissions, b.denied_permissions,
                       p.id as principal_id, p.type, p.display_name, p.external_id,
                       rt.code as template_code, rt.name as template_name, rt.permissions as template_permissions
                  from role_binding b join principal p on p.id = b.principal_id
                  left join role_template rt on rt.id = b.role_template_id
                 where b.id = :id and b.workspace_id = :ws
                """).param("id", id).param("ws", workspace).query((rs, n) -> binding(rs)).optional()
                .orElseThrow(() -> LqmException.notFound("binding"));
    }

    private static Map<String, Object> binding(java.sql.ResultSet rs) throws SQLException {
        List<String> template = strings(rs.getArray("template_permissions"));
        List<String> extra = strings(rs.getArray("extra_permissions"));
        List<String> denied = strings(rs.getArray("denied_permissions"));
        Map<String, Object> principal = new LinkedHashMap<>();
        principal.put("id", rs.getObject("principal_id", UUID.class));
        principal.put("type", rs.getString("type"));
        principal.put("displayName", rs.getString("display_name"));
        principal.put("externalId", rs.getString("external_id"));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getObject("id", UUID.class));
        m.put("principal", principal);
        m.put("roleTemplate", rs.getString("template_code"));
        m.put("roleTemplateName", rs.getString("template_name"));
        m.put("permissions", Permissions.effective(template, extra, denied));
        m.put("extraPermissions", extra);
        m.put("deniedPermissions", denied);
        m.put("scopeType", rs.getString("scope_type"));
        m.put("scopeId", rs.getObject("scope_id", UUID.class));
        m.put("approver", rs.getString("approver"));
        Timestamp expires = rs.getTimestamp("expires_at");
        m.put("expiresAt", expires == null ? null : expires.toInstant());
        m.put("createdAt", rs.getTimestamp("created_at").toInstant());
        return m;
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }

    /** The text form of a Postgres array literal, cast on the way in. */
    private static String pgArray(List<String> values) {
        return values.isEmpty() ? "{}" : "{" + values.stream()
                .map(v -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",")) + "}";
    }
}
