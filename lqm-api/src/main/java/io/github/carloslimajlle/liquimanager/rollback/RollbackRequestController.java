package io.github.carloslimajlle.liquimanager.rollback;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

import io.github.carloslimajlle.liquimanager.access.PermissionService;
import io.github.carloslimajlle.liquimanager.access.ScopeResolver;
import io.github.carloslimajlle.liquimanager.access.ScopeTarget;
import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;
import io.github.carloslimajlle.liquimanager.access.ReadScope;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asking for a targeted rollback, and deciding on it (RF-AUT-04).
 *
 * <p>The rules, all enforced here:
 * <ul>
 *   <li>The caller needs {@code rollback:<environment>} for the environment of the operation.
 *   <li>A changeset with no rollback and no automatic inverse cannot be requested (LQM-040): the button should
 *       be disabled, and the API agrees.
 *   <li>In a PRODUCTION environment the request waits for somebody else, holding {@code rollback:approve}, who is
 *       also not the author of the changeset (SoD-1, SoD-2). Elsewhere it is approved at once.
 * </ul>
 * What happens after approval is the runner's business (see {@link RunnerController}).
 */
@RestController
@RequestMapping(Api.V1)
class RollbackRequestController {

    private static final int MIN_REASON = 10;

    record RequestBodyDto(String changeset, String tag, String reason) {
    }

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;
    private final PermissionService permissions;

    private final io.github.carloslimajlle.liquimanager.notifications.NotificationService notifications;

    private final ScopeResolver scopes;
    private final ReadScope read;
    private final RollbackPlan plans;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    RollbackRequestController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit, PermissionService permissions,
                              io.github.carloslimajlle.liquimanager.notifications.NotificationService notifications,
                              ScopeResolver scopes, ReadScope read, RollbackPlan plans) {
        this.plans = plans;
        this.read = read;
        this.scopes = scopes;
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.audit = audit;
        this.permissions = permissions;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------ preview

    /**
     * What going back to a changeset would undo, before anybody asks for it: the changeset and everything applied after it on the
     * connection of this operation, newest first, which is the order it would be undone in.
     */
    @GetMapping("/operations/{id}/rollback-preview")
    Map<String, Object> preview(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                @PathVariable UUID id, @RequestParam String changeset) {
        UUID workspace = workspaces.resolve(ws);
        var operation = loadOperation(workspace, id);
        List<RollbackPlan.Item> items = plans.to((UUID) operation.get("db_connection_id"), targetOf(id, changeset));
        List<String> blockers = RollbackPlan.withoutRollback(items);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connection", operation.get("connection"));
        out.put("environment", operation.get("env"));
        out.put("total", items.size());
        out.put("reversible", blockers.isEmpty());
        out.put("blockers", blockers);
        out.put("items", items.stream().map(RollbackPlan.Item::toMap).toList());
        return out;
    }

    /**
     * What is applied on a connection, newest first, each with the operation that applied it: the list the project's Rollback tab
     * shows, where a person picks the changeset to go back to (and then asks {@code rollback-preview} and the request with that
     * operation).
     */
    @GetMapping("/connections/{id}/applied-changesets")
    Map<String, Object> applied(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        var connection = jdbc.sql("""
                select c.slug, e.code as env from db_connection c join environment e on e.id = c.environment_id
                 where c.id = :id and c.workspace_id = :ws
                """).param("id", id).param("ws", workspace).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("connection"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connection", connection.get("slug"));
        out.put("environment", connection.get("env"));
        out.put("items", plans.applied(id).stream().map(RollbackPlan.Item::toMap).toList());
        return out;
    }

    // ------------------------------------------------------------------ request

    @PostMapping("/operations/{id}/rollback-requests")
    @Transactional
    ResponseEntity<Map<String, Object>> request(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @PathVariable UUID id, @RequestBody RequestBodyDto body) {
        UUID workspace = workspaces.resolve(ws);
        PermissionService.Identity who = PermissionService.identityOf(authentication);
        String actor = AuditService.actorOf(authentication);

        boolean byChangeset = body.changeset() != null && !body.changeset().isBlank();
        boolean byTag = body.tag() != null && !body.tag().isBlank();
        if (byChangeset == byTag) {
            throw LqmException.invalid("send exactly one of changeset or tag");
        }
        if (body.reason() == null || body.reason().trim().length() < MIN_REASON) {
            throw LqmException.invalid("a reason of at least " + MIN_REASON + " characters is required");
        }

        var operation = loadOperation(workspace, id);
        if (!"database-change".equals(operation.get("type"))) {
            throw LqmException.invalid("only an operation that changed a database can be rolled back");
        }
        String env = (String) operation.get("env");
        UUID project = (UUID) operation.get("project_id");
        // The connection the operation ran on (its environment, linked projects, pipelines) and the operation's own project.
        ScopeTarget target = scopes.forConnection((UUID) operation.get("db_connection_id")).union(ScopeTarget.ofProject(project));
        if (!permissions.allowed(workspace, who, "rollback:" + env.toLowerCase(), target)) {
            throw LqmException.forbidden("missing permission rollback:" + env.toLowerCase());
        }

        // Going back to a changeset undoes it and what came after it; the list is frozen here, for the approver and for the runner.
        UUID changesetId = null;
        String plan = null;
        UUID planOperation = null;
        int count = 0;
        if (byChangeset) {
            changesetId = targetOf(id, body.changeset());
            List<RollbackPlan.Item> items = plans.to((UUID) operation.get("db_connection_id"), changesetId);
            List<String> blockers = RollbackPlan.withoutRollback(items);
            if (!blockers.isEmpty()) {
                throw new LqmException(HttpStatus.UNPROCESSABLE_ENTITY, "LQM-040",
                        "going back to " + body.changeset() + " would also undo changesets with no rollback and no automatic inverse: "
                                + String.join(", ", blockers));
            }
            plan = JSON.writeValueAsString(items.stream().map(RollbackPlan.Item::toMap).toList());
            // The changelog the runner reads is the one of the newest operation that applied something in the list, when it says which
            // commit it ran (a rollback's own operation may not); otherwise the operation the person is looking at.
            planOperation = items.stream().map(RollbackPlan.Item::operationId).filter(java.util.Objects::nonNull)
                    .filter(this::hasChangelog).findFirst().orElse(null);
            count = items.size();
        }

        // Production waits for another person; any other environment goes straight to the runner.
        boolean production = Boolean.TRUE.equals(operation.get("is_production"));
        String status = production ? "REQUESTED" : "APPROVED";
        UUID requestId = UuidV7.next();
        jdbc.sql("""
                insert into rollback_request (id, workspace_id, operation_id, operation_started_at, db_connection_id, kind,
                    changeset_id, tag, reason, status, requested_by, approved_by, decided_at, plan, plan_operation_id)
                values (:id, :ws, :op, :opAt, :conn, :kind, :cs, :tag, :reason, :status, :by, :approver, :decided, :plan, :planOp)
                """)
                .param("plan", plan).param("planOp", planOperation)
                .param("id", requestId).param("ws", workspace).param("op", id).param("opAt", operation.get("started_at"))
                .param("conn", operation.get("db_connection_id")).param("kind", byChangeset ? "CHANGESET" : "TAG")
                .param("cs", changesetId).param("tag", byTag ? body.tag().trim() : null).param("reason", body.reason().trim())
                .param("status", status).param("by", actor).param("approver", production ? null : "auto")
                .param("decided", production ? null : Timestamp.from(java.time.Instant.now())).update();

        Map<String, Object> created = load(workspace, requestId);
        audit.record(workspace, actor, "rollback.request", "rollback-request", requestId.toString(), null, created);
        if (production) {
            notifications.notify(workspace, "ROLLBACK_PENDING", null, env, "Rollback aguardando aprovacao em " + env,
                    actor + " pediu o rollback " + (byChangeset ? "ate o changeset " + body.changeset() + " (" + count + " a desfazer)" : "ate a tag " + body.tag())
                            + " em " + created.get("connection") + " (" + env + "). Motivo: " + body.reason().trim(),
                    "rollback:" + requestId);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ----------------------------------------------------------------- decision

    @PostMapping("/rollback-requests/{id}/approve")
    @Transactional
    Map<String, Object> approve(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        String actor = AuditService.actorOf(authentication);
        Map<String, Object> before = load(workspace, id);
        requireStatus(before, "REQUESTED");
        if (actor.equalsIgnoreCase((String) before.get("requestedBy"))) {
            throw sod("SoD-2: whoever requested a rollback cannot approve it");
        }
        // Whoever wrote any of the changesets that would be undone cannot approve it (SoD-1).
        java.util.Set<String> authors = new java.util.HashSet<>();
        if (before.get("changesetAuthor") != null) {
            authors.add(before.get("changesetAuthor").toString().toLowerCase());
        }
        for (Map<String, Object> item : planItems(before.get("plan"))) {
            if (item.get("author") != null) {
                authors.add(item.get("author").toString().toLowerCase());
            }
        }
        if (authors.contains(actor.toLowerCase())) {
            throw sod("SoD-1: the author of a changeset that would be undone cannot approve its rollback");
        }
        jdbc.sql("update rollback_request set status = 'APPROVED', approved_by = :by, decided_at = now() where id = :id")
                .param("by", actor).param("id", id).update();
        return decided(workspace, actor, "rollback.approve", id, before);
    }

    @PostMapping("/rollback-requests/{id}/reject")
    @Transactional
    Map<String, Object> reject(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = load(workspace, id);
        requireStatus(before, "REQUESTED");
        jdbc.sql("update rollback_request set status = 'REJECTED', decided_at = now() where id = :id").param("id", id).update();
        return decided(workspace, AuditService.actorOf(authentication), "rollback.reject", id, before);
    }

    /** The requester withdraws it, as long as no runner has taken it. */
    @PostMapping("/rollback-requests/{id}/cancel")
    @Transactional
    Map<String, Object> cancel(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        String actor = AuditService.actorOf(authentication);
        Map<String, Object> before = load(workspace, id);
        if (!"REQUESTED".equals(before.get("status")) && !"APPROVED".equals(before.get("status"))) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409", "the request is " + before.get("status") + ", too late to cancel");
        }
        if (!actor.equalsIgnoreCase((String) before.get("requestedBy"))) {
            throw LqmException.forbidden("only the person who asked can cancel the request");
        }
        jdbc.sql("update rollback_request set status = 'CANCELED', decided_at = now() where id = :id").param("id", id).update();
        return decided(workspace, actor, "rollback.cancel", id, before);
    }

    // ------------------------------------------------------------------ reading

    @GetMapping("/rollback-requests")
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) UUID operation,
                                   @RequestParam(required = false) UUID connection, Authentication authentication) {
        UUID workspace = workspaces.resolve(ws);
        ReadScope.Filter readable = read.of(workspace, authentication).connections("x.db_connection_id", "visibleConnections");
        return jdbc.sql(SELECT + """
                 where r.workspace_id = :ws
                   and (cast(:status as text) is null or r.status = :status)
                   and (cast(:op as uuid) is null or r.operation_id = :op)
                   and (cast(:conn as uuid) is null or r.db_connection_id = :conn)
                """ + (readable.sql().isEmpty() ? "" : " and r.operation_id in (select x.id from operation x where true" + readable.sql() + ")")
                + " order by r.created_at desc limit 100")
                .param("ws", workspace).param("status", blank(status)).param("op", operation).param("conn", connection).params(readable.params())
                .query().listOfRows().stream().map(RollbackRequestController::present).toList();
    }

    @GetMapping("/rollback-requests/{id}")
    Map<String, Object> get(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID id) {
        return load(workspaces.resolve(ws), id);
    }

    // ------------------------------------------------------------------ helpers

    private static final String SELECT = """
            select r.plan, r.id, r.operation_id, r.kind, r.tag, r.reason, r.status, r.requested_by, r.approved_by, r.decided_at,
                   r.claimed_by, r.claimed_at, r.finished_at, r.result_operation_id, r.error, r.created_at,
                   cs.cs_id as changeset, cs.id as changeset_uuid, cs.author as changeset_author, c.slug as connection, e.code as environment
              from rollback_request r
              join db_connection c on c.id = r.db_connection_id
              join environment e on e.id = c.environment_id
              left join changeset cs on cs.id = r.changeset_id
            """;

    private boolean hasChangelog(UUID operationId) {
        return jdbc.sql("select count(*) from operation where id = :id and changelog_id is not null and git_commit is not null")
                .param("id", operationId).query(Long.class).single() > 0;
    }

    private Map<String, Object> loadOperation(UUID workspace, UUID id) {
        return jdbc.sql("""
                select o.id, o.started_at, o.db_connection_id, o.type, o.status, o.project_id, e.code as env, e.is_production,
                       c.slug as connection
                  from operation o join db_connection c on c.id = o.db_connection_id join environment e on e.id = c.environment_id
                 where o.id = :id and o.workspace_id = :ws
                """).param("id", id).param("ws", workspace).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("operation"));
    }

    /** The changeset (by its id in the changelog) among those this operation evaluated. */
    private UUID targetOf(UUID operationId, String changesetId) {
        List<Map<String, Object>> found = jdbc.sql("""
                select cs.id from operation_changeset oc join changeset cs on cs.id = oc.changeset_id
                 where oc.operation_id = :op and cs.cs_id = :csId
                """).param("op", operationId).param("csId", changesetId).query().listOfRows();
        if (found.isEmpty()) {
            throw LqmException.invalid("the operation has no changeset " + changesetId);
        }
        return (UUID) found.get(0).get("id");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> planItems(Object plan) {
        return plan instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private Map<String, Object> load(UUID workspace, UUID id) {
        return jdbc.sql(SELECT + " where r.id = :id and r.workspace_id = :ws").param("id", id).param("ws", workspace)
                .query().listOfRows().stream().findFirst().map(RollbackRequestController::present)
                .orElseThrow(() -> LqmException.notFound("rollback request"));
    }

    private Map<String, Object> decided(UUID workspace, String actor, String action, UUID id, Map<String, Object> before) {
        Map<String, Object> after = load(workspace, id);
        audit.record(workspace, actor, action, "rollback-request", id.toString(), before, after);
        return after;
    }

    private static void requireStatus(Map<String, Object> request, String expected) {
        if (!expected.equals(request.get("status"))) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409", "the request is " + request.get("status") + ", not " + expected);
        }
    }

    private static LqmException sod(String message) {
        return new LqmException(HttpStatus.CONFLICT, "LQM-010", message);
    }

    static Map<String, Object> present(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("operationId", r.get("operation_id"));
        m.put("kind", r.get("kind"));
        m.put("changeset", r.get("changeset"));
        m.put("changesetUuid", r.get("changeset_uuid"));
        m.put("changesetAuthor", r.get("changeset_author"));
        m.put("tag", r.get("tag"));
        // What going back to the changeset undoes, newest first: frozen when it was requested.
        if (r.get("plan") != null) {
            List<Map<String, Object>> items = JSON.readValue(r.get("plan").toString(), List.class);
            m.put("plan", items);
            m.put("count", items.size());
        }
        m.put("reason", r.get("reason"));
        m.put("status", r.get("status"));
        m.put("connection", r.get("connection"));
        m.put("environment", r.get("environment"));
        m.put("requestedBy", r.get("requested_by"));
        m.put("approvedBy", r.get("approved_by"));
        m.put("decidedAt", instant(r.get("decided_at")));
        m.put("claimedBy", r.get("claimed_by"));
        m.put("finishedAt", instant(r.get("finished_at")));
        m.put("resultOperationId", r.get("result_operation_id"));
        m.put("error", r.get("error"));
        m.put("createdAt", instant(r.get("created_at")));
        return m;
    }

    private static Object instant(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
