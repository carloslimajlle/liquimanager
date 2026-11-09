package io.github.carloslimajlle.liquimanager.catalog;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.stream.ClusterBus;

/**
 * The "secrets management" panel (screen 08): which connections have credentials, from which provider,
 * and how long ago they were rotated. It never returns a value, nor even the reference: a pointer into a
 * vault is not secret, but it is not the panel's business either (docs/09 § 5).
 *
 * <p>Rotation itself happens in the vault. LiquiManager cannot see it, so somebody (or the vault's own
 * hook) tells it with {@code POST /secrets/{connection}/rotated}; until then the age keeps growing,
 * which is the point of the panel.
 */
@RestController
@RequestMapping(Api.V1 + "/secrets")
class SecretsController {

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final AuditService audit;
    private final SecretResolver resolver;
    private final int warnDays;
    private final int dangerDays;
    private final ClusterBus bus;

    SecretsController(JdbcClient jdbc, WorkspaceContext workspaces, AuditService audit, SecretResolver resolver, ClusterBus bus,
                      @Value("${lqm.secrets.rotation-warn-days:14}") int warnDays,
                      @Value("${lqm.secrets.rotation-danger-days:30}") int dangerDays) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.audit = audit;
        this.resolver = resolver;
        this.bus = bus;
        this.warnDays = warnDays;
        this.dangerDays = dangerDays;
    }

    @GetMapping("/status")
    Map<String, Object> status(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws) {
        UUID workspace = workspaces.resolve(ws);
        Instant now = Instant.now();
        List<Map<String, Object>> connections = jdbc.sql("""
                select c.id, c.slug, e.code as environment, c.secret_provider, c.secret_rotated_at
                  from db_connection c join environment e on e.id = c.environment_id
                 where c.workspace_id = :ws and c.archived_at is null
                   and c.secret_provider is not null and c.secret_provider <> 'NONE'
                 order by e.sort_order, c.slug
                """).param("ws", workspace).query().listOfRows().stream().map(r -> {
            Timestamp rotated = (Timestamp) r.get("secret_rotated_at");
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("connection", r.get("slug"));
            m.put("environment", r.get("environment"));
            m.put("provider", r.get("secret_provider"));
            m.put("rotatedAt", rotated == null ? null : rotated.toInstant());
            // Never rotated, as far as anyone has said, is the worst case, not "age zero".
            Long age = rotated == null ? null : Duration.between(rotated.toInstant(), now).toDays();
            m.put("ageDays", age);
            m.put("level", level(age));
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("connections", connections);
        // Distinct changesets flagged, not findings: one changeset seen in four environments is one secret in one file.
        out.put("secretsInChangelogs", jdbc.sql("""
                select count(distinct f.changeset_id)
                  from policy_finding f
                  join policy_check pc on pc.id = f.check_id
                  join changeset cs on cs.id = f.changeset_id
                  join changelog cl on cl.id = cs.changelog_id
                  join project p on p.id = cl.project_id
                 where pc.code = 'SecretInChangelog' and p.workspace_id = :ws
                """).param("ws", workspace).query(Long.class).single());
        return out;
    }

    @PostMapping("/{connection}/rotated")
    @Transactional
    Map<String, Object> rotated(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication, @PathVariable String connection) {
        UUID workspace = workspaces.resolve(ws);
        // The map is what carries the (possibly null) date: an Optional of a null value would read as "no such connection".
        Map<String, Object> row = jdbc.sql("select id, secret_rotated_at from db_connection where workspace_id = :ws and slug = :slug")
                .param("ws", workspace).param("slug", connection).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("connection " + connection));
        Timestamp before = (Timestamp) row.get("secret_rotated_at");
        Instant now = Instant.now();
        jdbc.sql("update db_connection set secret_rotated_at = :now where workspace_id = :ws and slug = :slug")
                .param("now", Timestamp.from(now)).param("ws", workspace).param("slug", connection).update();
        // The old credentials may still be cached; they are exactly the ones that just stopped being valid.
        resolver.evictAll();
        // The other replicas keep their own cache: tell them too (the one that received this call already forgot).
        bus.publish(SecretCacheSync.EVICT, java.util.Map.of());
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("connection", connection);
        change.put("rotatedAt", now);
        audit.record(workspace, AuditService.actorOf(authentication), "secret.rotated", "connection", connection,
                before == null ? null : Map.of("rotatedAt", before.toInstant()), change);
        return change;
    }

    private String level(Long ageDays) {
        if (ageDays == null || ageDays >= dangerDays) {
            return "danger";
        }
        return ageDays >= warnDays ? "warning" : "ok";
    }
}
