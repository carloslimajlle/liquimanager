package io.github.carloslimajlle.liquimanager.exceptions;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
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

import io.github.carloslimajlle.liquimanager.access.ReadScope;
import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * Policy exceptions: a temporary, approved permission for ONE check not to block (docs/08 § 1).
 *
 * <p>The rules, all enforced here and not on the screen:
 * <ul>
 *   <li>It always expires, and not later than {@code lqm.exceptions.max-days} from now.
 *   <li>SoD-2: whoever asked cannot approve (the database has the same rule as a constraint).
 *   <li>SoD-1: the author of the changeset it covers cannot approve it.
 *   <li>An exception past its expiry cannot be approved (LQM-030), and one that lapses simply
 *       stops being served to collectors — no job has to run for the gate to start blocking again.
 * </ul>
 */
@RestController
@RequestMapping(Api.V1 + "/policy-exceptions")
class PolicyExceptionController {

    private static final int MIN_JUSTIFICATION = 20;

    record RequestBodyDto(String check, String project, String changeset, String environment,
                          String justification, Instant expiresAt) {
    }

    record Decision(String reason) {
    }

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;
    private final int maxDays;
    private final ReadScope read;

    PolicyExceptionController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit, ReadScope read,
                              @Value("${lqm.exceptions.max-days:30}") int maxDays) {
        this.read = read;
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.audit = audit;
        this.maxDays = maxDays;
    }

    // -------------------------------------------------------------------- list

    @GetMapping
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                   @RequestParam(required = false) String status, Authentication authentication) {
        UUID workspace = workspaces.resolve(ws);
        // An exception about a project is read by whoever reads that project; one about no project (a rule for the whole workspace) by all.
        ReadScope.Filter visible = read.of(workspace, authentication).projects("e.project_id", "visibleProjects");
        String scope = visible.sql().isEmpty() ? "" : " and (e.project_id is null or " + visible.sql().substring(" and ".length()) + ")";
        return jdbc.sql(SELECT + " where e.workspace_id = :ws" + scope + " order by e.created_at desc limit 200")
                .param("ws", workspace).params(visible.params()).query().listOfRows().stream()
                .map(r -> present(r, Instant.now()))
                .filter(m -> status == null || status.isBlank() || status.equalsIgnoreCase((String) m.get("status")))
                .toList();
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                            @PathVariable UUID id) {
        return load(workspaces.resolve(ws), id);
    }

    // ----------------------------------------------------------------- request

    @PostMapping
    @Transactional
    ResponseEntity<Map<String, Object>> request(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @RequestBody RequestBodyDto body) {
        UUID workspace = workspaces.resolve(ws);
        String actor = AuditService.actorOf(authentication);

        if (body.justification() == null || body.justification().trim().length() < MIN_JUSTIFICATION) {
            throw LqmException.invalid("justification needs at least " + MIN_JUSTIFICATION + " characters");
        }
        Instant now = Instant.now();
        if (body.expiresAt() == null || !body.expiresAt().isAfter(now)) {
            throw LqmException.invalid("expiresAt must be in the future: an exception always expires");
        }
        if (body.expiresAt().isAfter(now.plus(Duration.ofDays(maxDays)))) {
            throw LqmException.invalid("expiresAt is more than " + maxDays + " days away, the workspace limit");
        }

        UUID checkId = jdbc.sql("select id from policy_check where code = :code and (workspace_id is null or workspace_id = :ws)")
                .param("code", body.check()).param("ws", workspace).query(UUID.class).optional()
                .orElseThrow(() -> LqmException.invalid("unknown check: " + body.check()));
        UUID projectId = null;
        if (body.project() != null && !body.project().isBlank()) {
            projectId = jdbc.sql("select id from project where workspace_id = :ws and slug = :slug")
                    .param("ws", workspace).param("slug", body.project()).query(UUID.class).optional()
                    .orElseThrow(() -> LqmException.invalid("unknown project: " + body.project()));
        }
        UUID environmentId = null;
        if (body.environment() != null && !body.environment().isBlank()) {
            environmentId = jdbc.sql("select id from environment where workspace_id = :ws and code = :code")
                    .param("ws", workspace).param("code", body.environment().toUpperCase())
                    .query(UUID.class).optional()
                    .orElseThrow(() -> LqmException.invalid("unknown environment: " + body.environment()));
        }
        UUID changesetId = null;
        if (body.changeset() != null && !body.changeset().isBlank()) {
            if (projectId == null) {
                throw LqmException.invalid("a changeset exception needs the project it belongs to");
            }
            List<UUID> found = jdbc.sql("""
                    select cs.id from changeset cs join changelog cl on cl.id = cs.changelog_id
                     where cl.project_id = :project and cs.cs_id = :id
                    """).param("project", projectId).param("id", body.changeset()).query(UUID.class).list();
            if (found.isEmpty()) {
                throw LqmException.invalid("changeset not found in the project: " + body.changeset());
            }
            changesetId = found.get(0);
        }

        UUID id = UuidV7.next();
        jdbc.sql("""
                insert into policy_exception (id, workspace_id, check_id, changeset_id, environment_id, project_id,
                    justification, requested_by, status, expires_at)
                values (:id, :ws, :check, :cs, :env, :project, :why, :by, 'REQUESTED', :expires)
                """)
                .param("id", id).param("ws", workspace).param("check", checkId).param("cs", changesetId)
                .param("env", environmentId).param("project", projectId).param("why", body.justification().trim())
                .param("by", actor).param("expires", OffsetDateTime.ofInstant(body.expiresAt(), ZoneOffset.UTC))
                .update();

        Map<String, Object> created = load(workspace, id);
        audit.record(workspace, actor, "exception.request", "policy-exception", id.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    // ------------------------------------------------------------- transitions

    @PostMapping("/{id}/approve")
    @Transactional
    Map<String, Object> approve(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        String actor = AuditService.actorOf(authentication);
        Map<String, Object> before = load(workspace, id);
        if ("EXPIRED".equals(before.get("status"))) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-030", "the exception has already expired");
        }
        requireStatus(before, "REQUESTED");

        if (actor.equalsIgnoreCase((String) before.get("requestedBy"))) {
            throw sod("SoD-2: whoever requested an exception cannot approve it");
        }
        Object author = before.get("changesetAuthor");
        if (author != null && actor.equalsIgnoreCase(author.toString())) {
            throw sod("SoD-1: the author of the changeset cannot approve an exception for it");
        }
        if (!((Instant) before.get("expiresAt")).isAfter(Instant.now())) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-030", "the exception has already expired");
        }
        jdbc.sql("update policy_exception set status = 'APPROVED', approved_by = :by where id = :id")
                .param("by", actor).param("id", id).update();
        return decided(workspace, actor, "exception.approve", id, before);
    }

    @PostMapping("/{id}/reject")
    @Transactional
    Map<String, Object> reject(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id,
                               @RequestBody(required = false) Decision decision) {
        UUID workspace = workspaces.resolve(ws);
        String actor = AuditService.actorOf(authentication);
        Map<String, Object> before = load(workspace, id);
        requireStatus(before, "REQUESTED");
        jdbc.sql("update policy_exception set status = 'REJECTED' where id = :id").param("id", id).update();
        return decided(workspace, actor, "exception.reject", id, before);
    }

    /** Ends an approved exception early. The gate blocks again on the next evaluation. */
    @PostMapping("/{id}/revoke")
    @Transactional
    Map<String, Object> revoke(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        String actor = AuditService.actorOf(authentication);
        Map<String, Object> before = load(workspace, id);
        requireStatus(before, "APPROVED");
        jdbc.sql("update policy_exception set status = 'REVOKED' where id = :id").param("id", id).update();
        return decided(workspace, actor, "exception.revoke", id, before);
    }

    // ----------------------------------------------------------------- helpers

    private Map<String, Object> decided(UUID workspace, String actor, String action, UUID id, Map<String, Object> before) {
        Map<String, Object> after = load(workspace, id);
        audit.record(workspace, actor, action, "policy-exception", id.toString(), before, after);
        return after;
    }

    private static LqmException sod(String message) {
        return new LqmException(HttpStatus.CONFLICT, "LQM-010", message);
    }

    private static void requireStatus(Map<String, Object> exception, String expected) {
        if (!expected.equals(exception.get("status"))) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409",
                    "the exception is " + exception.get("status") + ", not " + expected);
        }
    }

    private static final String SELECT = """
            select e.id, pc.code as check_code, cs.cs_id, cs.author as cs_author, p.slug as project,
                   env.code as environment, e.justification, e.requested_by, e.approved_by, e.status,
                   e.expires_at, e.created_at
              from policy_exception e
              join policy_check pc on pc.id = e.check_id
              left join changeset cs on cs.id = e.changeset_id
              left join project p on p.id = e.project_id
              left join environment env on env.id = e.environment_id
            """;

    private Map<String, Object> load(UUID workspace, UUID id) {
        var rows = jdbc.sql(SELECT + " where e.id = :id and e.workspace_id = :ws")
                .param("id", id).param("ws", workspace).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("policy exception");
        }
        return present(rows.get(0), Instant.now());
    }

    /** An APPROVED exception past its expiry reads as EXPIRED, whether or not any job has noticed. */
    private static Map<String, Object> present(Map<String, Object> r, Instant now) {
        Instant expires = ((Timestamp) r.get("expires_at")).toInstant();
        String stored = (String) r.get("status");
        String status = "APPROVED".equals(stored) && !expires.isAfter(now) ? "EXPIRED"
                : "REQUESTED".equals(stored) && !expires.isAfter(now) ? "EXPIRED" : stored;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        out.put("check", r.get("check_code"));
        out.put("changeset", r.get("cs_id"));
        out.put("changesetAuthor", r.get("cs_author"));
        out.put("project", r.get("project"));
        out.put("environment", r.get("environment"));
        out.put("justification", r.get("justification"));
        out.put("requestedBy", r.get("requested_by"));
        out.put("approvedBy", r.get("approved_by"));
        out.put("status", status);
        out.put("expiresAt", expires);
        out.put("createdAt", ((Timestamp) r.get("created_at")).toInstant());
        return out;
    }
}
