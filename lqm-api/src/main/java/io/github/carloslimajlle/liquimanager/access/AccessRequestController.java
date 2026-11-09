package io.github.carloslimajlle.liquimanager.access;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Asking for access. Somebody who signed in and holds no permission asks (with a line saying who they are and why), and whoever administers
 * access sees the request in the bell and on the access screen. It asks and tells; it grants NOTHING: the administrator chooses the role and
 * the scope, and granting is the ordinary binding.
 *
 * <p>One open request per person. Asking again within a day answers with the one already open, so a click is not a flood; after a day it
 * is renewed (and rings the bell again). A request closes by itself when the person, or one of their groups, gets any binding.
 */
@RestController
@RequestMapping(Api.V1 + "/access-requests")
class AccessRequestController {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_MESSAGE = 500;

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;

    AccessRequestController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.audit = audit;
    }

    record AskBody(String message) {
    }

    record DismissBody(String note) {
    }

    /** The caller asks for access. 201 when a request was opened or renewed, 200 with the open one when it is too soon to ask again. */
    @PostMapping
    @Transactional
    ResponseEntity<Map<String, Object>> ask(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                            Authentication authentication, @RequestBody(required = false) AskBody body) {
        UUID workspace = workspaces.resolve(ws);
        PermissionService.Identity who = PermissionService.identityOf(authentication);
        if (who.open() || who.name() == null || who.name().isBlank()) {
            throw LqmException.invalid("only a signed-in person can ask for access");
        }
        String message = body == null || body.message() == null || body.message().isBlank() ? null : body.message().trim();
        if (message != null && message.length() > MAX_MESSAGE) {
            throw LqmException.invalid("the message can have at most " + MAX_MESSAGE + " characters");
        }
        resolveGranted(workspace);

        var open = jdbc.sql("""
                select id, requested_at, (requested_at > now() - interval '1 day') as recent
                  from access_request where workspace_id = :ws and lower(requester) = lower(:who) and status = 'OPEN'
                """).param("ws", workspace).param("who", who.name()).query().listOfRows().stream().findFirst();
        if (open.isPresent() && Boolean.TRUE.equals(open.get().get("recent"))) {
            return ResponseEntity.ok(present(load(workspace, (UUID) open.get().get("id")), true));
        }
        String groups = JSON.writeValueAsString(who.groups());
        UUID id;
        if (open.isPresent()) {
            id = (UUID) open.get().get("id");
            jdbc.sql("""
                    update access_request set requested_at = now(), email = :email, groups = :groups, message = coalesce(:message, message)
                     where id = :id
                    """).param("email", who.email()).param("groups", groups).param("message", message).param("id", id).update();
        } else {
            id = UuidV7.next();
            jdbc.sql("""
                    insert into access_request (id, workspace_id, requester, email, groups, message, status)
                    values (:id, :ws, :who, :email, :groups, :message, 'OPEN')
                    """).param("id", id).param("ws", workspace).param("who", who.name()).param("email", who.email())
                    .param("groups", groups).param("message", message).update();
        }
        Map<String, Object> created = present(load(workspace, id), false);
        audit.record(workspace, who.name(), "access.request", "access-request", id.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** The caller's own open request, if any: what the welcome screen shows instead of the button once it was asked. */
    @GetMapping("/mine")
    Map<String, Object> mine(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, Authentication authentication) {
        UUID workspace = workspaces.resolve(ws);
        PermissionService.Identity who = PermissionService.identityOf(authentication);
        resolveGranted(workspace);
        return jdbc.sql("select id from access_request where workspace_id = :ws and lower(requester) = lower(:who) and status = 'OPEN'")
                .param("ws", workspace).param("who", who.name() == null ? "" : who.name()).query(UUID.class).optional()
                .map(id -> present(load(workspace, id), false)).orElseGet(LinkedHashMap::new);
    }

    /** The open requests (needs assignment:admin), oldest first: the line at the door. */
    @GetMapping
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                   @RequestParam(defaultValue = "OPEN") String status) {
        UUID workspace = workspaces.resolve(ws);
        resolveGranted(workspace);
        return jdbc.sql(SELECT + " where workspace_id = :ws and status = :status order by requested_at")
                .param("ws", workspace).param("status", status.toUpperCase()).query().listOfRows().stream()
                .map(row -> present(row, false)).toList();
    }

    /** Sets a request aside without granting (needs assignment:admin): it was not meant, or it is handled some other way. */
    @PostMapping("/{id}/dismiss")
    @Transactional
    Map<String, Object> dismiss(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication, @PathVariable UUID id, @RequestBody(required = false) DismissBody body) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = present(load(workspace, id), false);
        if (!"OPEN".equals(before.get("status"))) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409", "the request is " + before.get("status") + ", not OPEN");
        }
        String actor = AuditService.actorOf(authentication);
        String note = body == null || body.note() == null || body.note().isBlank() ? null : body.note().trim();
        jdbc.sql("update access_request set status = 'DISMISSED', resolved_at = now(), resolved_by = :by, resolved_note = :note where id = :id")
                .param("by", actor).param("note", note).param("id", id).update();
        Map<String, Object> after = present(load(workspace, id), false);
        audit.record(workspace, actor, "access.request.dismiss", "access-request", id.toString(), before, after);
        return after;
    }

    // ------------------------------------------------------------------ helpers

    /** An open request whose person, or one of whose groups, now has a binding is granted: nobody has to close it. */
    private void resolveGranted(UUID workspace) {
        jdbc.sql("""
                update access_request r set status = 'GRANTED', resolved_at = now(), resolved_by = 'system'
                 where r.workspace_id = :ws and r.status = 'OPEN'
                   and exists (
                       select 1 from role_binding b join principal p on p.id = b.principal_id
                        where b.workspace_id = r.workspace_id
                          and (lower(p.external_id) = lower(r.requester)
                               or lower(p.external_id) in (select lower(g) from jsonb_array_elements_text(r.groups::jsonb) as g)))
                """).param("ws", workspace).update();
    }

    private static final String SELECT = """
            select id, requester, email, groups, message, status, requested_at, created_at, resolved_at, resolved_by, resolved_note
              from access_request
            """;

    private Map<String, Object> load(UUID workspace, UUID id) {
        return jdbc.sql(SELECT + " where id = :id and workspace_id = :ws").param("id", id).param("ws", workspace)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> LqmException.notFound("access request"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> present(Map<String, Object> row, boolean already) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.get("id"));
        m.put("requester", row.get("requester"));
        m.put("email", row.get("email"));
        m.put("groups", JSON.readValue(row.get("groups").toString(), List.class));
        m.put("message", row.get("message"));
        m.put("status", row.get("status"));
        m.put("requestedAt", instant(row.get("requested_at")));
        m.put("resolvedAt", instant(row.get("resolved_at")));
        m.put("resolvedBy", row.get("resolved_by"));
        m.put("resolvedNote", row.get("resolved_note"));
        if (already) {
            m.put("already", true);
        }
        return m;
    }

    private static Object instant(Object timestamp) {
        return timestamp instanceof Timestamp t ? t.toInstant() : timestamp;
    }
}
