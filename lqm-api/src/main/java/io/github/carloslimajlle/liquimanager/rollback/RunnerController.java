package io.github.carloslimajlle.liquimanager.rollback;

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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.security.ServiceTokenAuthentication;

/**
 * What a rollback runner talks to (token scope {@code runner}).
 *
 * <p>The runner PULLS: it asks for the next approved request, does the rollback itself, and says how it went. The
 * API starts nothing, so it needs no rights on any cluster; whatever starts the runner (a Kubernetes Job, a
 * pipeline job, a person at a terminal) is outside this class. What the runner is told is what it needs to find the
 * changelog and the database, and never a credential: it resolves those where it runs.
 */
@RestController
@RequestMapping(Api.V1 + "/runner")
class RunnerController {

    record Completion(boolean ok, UUID resultOperationId, String error) {
    }

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private final JdbcClient jdbc;
    private final AuditService audit;

    RunnerController(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** The oldest approved request, now this runner's; 204 when there is none. */
    @PostMapping("/claim")
    @Transactional
    ResponseEntity<Map<String, Object>> claim(Authentication authentication) {
        var token = ((ServiceTokenAuthentication) authentication).getPrincipal();
        Optional<Map<String, Object>> next = jdbc.sql("""
                select r.id from rollback_request r
                 where r.workspace_id = :ws and r.status = 'APPROVED'
                 order by r.created_at limit 1 for update skip locked
                """).param("ws", token.workspaceId()).query().listOfRows().stream().findFirst();
        if (next.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        UUID id = (UUID) next.get().get("id");
        jdbc.sql("update rollback_request set status = 'RUNNING', claimed_by = :by, claimed_at = now() where id = :id")
                .param("by", token.name()).param("id", id).update();

        var job = jdbc.sql("""
                select r.id, r.kind, r.tag, r.operation_id, r.plan, cs.cs_id, cs.author, cs.file_path,
                       c.slug as connection, c.jdbc_url, c.default_schema, e.code as environment,
                       p.slug as project, cl.path as changelog_path, o.git_commit, o.contexts, o.labels
                  from rollback_request r
                  join db_connection c on c.id = r.db_connection_id
                  join environment e on e.id = c.environment_id
                  join operation o on o.id = coalesce(r.plan_operation_id, r.operation_id)
                  left join changeset cs on cs.id = r.changeset_id
                  left join project p on p.id = o.project_id
                  left join changelog cl on cl.id = o.changelog_id
                 where r.id = :id
                """).param("id", id).query().singleRow();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", job.get("id"));
        out.put("kind", job.get("kind"));
        out.put("tag", job.get("tag"));
        out.put("parentOperationId", job.get("operation_id"));
        // What it is to undo, newest first, as id::author::filePath: the runner refuses to go on if the database says otherwise.
        if (job.get("plan") != null) {
            List<Map<String, Object>> items = JSON.readValue(job.get("plan").toString(), List.class);
            out.put("plan", items.stream().map(i -> i.get("changesetId") + "::" + i.get("author") + "::" + i.get("filePath")).toList());
        }
        Map<String, Object> changeset = new LinkedHashMap<>();
        changeset.put("id", job.get("cs_id"));
        changeset.put("author", job.get("author"));
        changeset.put("filePath", job.get("file_path"));
        out.put("changeset", job.get("cs_id") == null ? null : changeset);
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("key", job.get("connection"));
        connection.put("jdbcUrl", job.get("jdbc_url"));
        // A list of schemas ("dbo, audit") is for drift; a job runs Liquibase in the first one, where its own tables are.
        Object schemas = job.get("default_schema");
        connection.put("defaultSchema", schemas == null ? null : schemas.toString().split(",")[0].trim());
        out.put("connection", connection);
        out.put("environment", job.get("environment"));
        out.put("project", job.get("project"));
        out.put("changelogPath", job.get("changelog_path"));
        out.put("gitCommit", job.get("git_commit"));
        out.put("contexts", job.get("contexts"));
        out.put("labels", job.get("labels"));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/requests/{id}/complete")
    @Transactional
    Map<String, Object> complete(Authentication authentication, @PathVariable UUID id, @RequestBody Completion body) {
        var token = ((ServiceTokenAuthentication) authentication).getPrincipal();
        var row = jdbc.sql("select status, claimed_by from rollback_request where id = :id and workspace_id = :ws")
                .param("id", id).param("ws", token.workspaceId()).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("rollback request"));
        if (!"RUNNING".equals(row.get("status"))) {
            throw new LqmException(HttpStatus.CONFLICT, "LQM-409", "the request is " + row.get("status") + ", not RUNNING");
        }
        // Only the runner that took it can finish it: another one might be about to do the same rollback.
        if (!token.name().equals(row.get("claimed_by"))) {
            throw LqmException.forbidden("this request was claimed by another runner");
        }
        jdbc.sql("""
                update rollback_request set status = :status, finished_at = now(), result_operation_id = :result, error = :error
                 where id = :id
                """).param("status", body.ok() ? "DONE" : "FAILED").param("result", body.resultOperationId())
                .param("error", body.ok() ? null : (body.error() == null ? "the runner reported a failure" : body.error()))
                .param("id", id).update();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", body.ok() ? "DONE" : "FAILED");
        after.put("resultOperationId", body.resultOperationId());
        after.put("error", body.error());
        audit.record(null, "runner:" + token.name(), "rollback.execute", "rollback-request", id.toString(), null, after);
        return Map.of("id", id, "status", after.get("status"));
    }
}
