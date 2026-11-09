package io.github.carloslimajlle.liquimanager.catalog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
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
import io.github.carloslimajlle.liquimanager.drift.SqlServerAccess;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;
import io.github.carloslimajlle.liquimanager.ingest.JdbcFingerprint;

/**
 * Database connections (RF-CAD-05): one physical or logical database in one
 * environment. The row holds a JDBC URL WITHOUT credentials and only a reference to
 * the secret (RNF-06) — "test connection" resolves it server-side, so no credential
 * ever reaches the browser.
 */
@RestController
@RequestMapping(Api.V1 + "/connections")
class ConnectionController {

    private static final String COLUMNS = """
            id, environment_id, slug, display_name, engine, jdbc_url, default_schema, host_fingerprint,
            secret_provider, secret_ref, secret_rotated_at, drift_enabled, drift_cron, scanner_enabled,
            scanner_interval_min, db_version, last_test_at, last_test_ok, verified, archived_at
            """;

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final SecretResolver secrets;
    private final AuditService audit;
    private final ReadScope read;

    ConnectionController(JdbcClient jdbc, WorkspaceContext workspaces, SecretResolver secrets, AuditService audit, ReadScope read) {
        this.read = read;
        this.audit = audit;
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.secrets = secrets;
    }

    record ConnectionRequest(UUID environmentId, String slug, String displayName, String engine, String jdbcUrl,
                             String defaultSchema, String secretProvider, String secretRef, Boolean driftEnabled,
                             String driftCron, Boolean scannerEnabled, Integer scannerIntervalMin,
                             Boolean verified, List<UUID> projectIds) {
    }

    @GetMapping
    List<Map<String, Object>> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                   Authentication authentication) {
        UUID workspace = workspaces.resolve(ws);
        ReadScope.Filter visible = read.of(workspace, authentication).connections("id", "visibleConnections");
        return jdbc.sql("select " + COLUMNS + " from db_connection where workspace_id = :ws "
                        + "and archived_at is null" + visible.sql() + " order by slug")
                .param("ws", workspace).params(visible.params()).query().listOfRows().stream().map(this::present).toList();
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                            @PathVariable UUID id) {
        return present(row(workspaces.resolve(ws), id));
    }

    @PostMapping
    @Transactional
    ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @RequestBody ConnectionRequest body) {
        UUID workspace = workspaces.resolve(ws);
        validate(body);
        UUID id = UuidV7.next();
        jdbc.sql("""
                insert into db_connection (id, workspace_id, environment_id, slug, display_name, engine,
                    jdbc_url, default_schema, host_fingerprint, secret_provider, secret_ref, drift_enabled,
                    drift_cron, scanner_enabled, scanner_interval_min, verified)
                values (:id, :ws, :env, :slug, :name, :engine, :url, :schema, :fp, :provider, :ref,
                    :drift, coalesce(:cron, '0 0 */6 * * *'), :scanner, coalesce(:interval, 15), :verified)
                """)
                .params(commonParams(body, workspace))
                .param("id", id)
                .update();
        linkProjects(id, body.projectIds());
        Map<String, Object> created = present(row(workspace, id));
        audit.record(workspace, AuditService.actorOf(authentication), "connection.create", "connection", id.toString(), null, created);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    @Transactional
    Map<String, Object> update(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               Authentication authentication, @PathVariable UUID id, @RequestBody ConnectionRequest body) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = present(row(workspace, id));
        validate(body);
        jdbc.sql("""
                update db_connection set environment_id = :env, slug = :slug, display_name = :name,
                    engine = :engine, jdbc_url = :url, default_schema = :schema, host_fingerprint = :fp,
                    secret_provider = :provider, secret_ref = :ref, drift_enabled = :drift,
                    drift_cron = coalesce(:cron, drift_cron), scanner_enabled = :scanner,
                    scanner_interval_min = coalesce(:interval, scanner_interval_min), verified = :verified
                 where id = :id and workspace_id = :ws
                """)
                .params(commonParams(body, workspace))
                .param("id", id)
                .update();
        if (body.projectIds() != null) {
            jdbc.sql("delete from project_connection where db_connection_id = :id").param("id", id).update();
            linkProjects(id, body.projectIds());
        }
        Map<String, Object> after = present(row(workspace, id));
        audit.record(workspace, AuditService.actorOf(authentication), "connection.update", "connection", id.toString(), before, after);
        return after;
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> archive(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                 Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> before = present(row(workspace, id));
        jdbc.sql("update db_connection set archived_at = now() where id = :id and workspace_id = :ws")
                .param("id", id).param("ws", workspace).update();
        audit.record(workspace, AuditService.actorOf(authentication), "connection.archive", "connection", id.toString(), before, null);
        return ResponseEntity.noContent().build();
    }

    /**
     * RF-CAD-05 CA1: resolves the secret on the server, opens a connection, and answers
     * with the database version. Failures are a normal RESULT (200 with ok=false), not
     * an HTTP error: "the database refused us" is exactly what the user came to learn.
     */
    @PostMapping("/{id}/test")
    Map<String, Object> test(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                             Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> connection = row(workspace, id);
        SecretResolver.Credentials credentials = secrets.resolve(
                (String) connection.get("secret_provider"), (String) connection.get("secret_ref"));

        Properties properties = new Properties();
        if (credentials.username() != null) {
            properties.setProperty("user", credentials.username());
        }
        if (credentials.password() != null) {
            properties.setProperty("password", credentials.password());
        }
        properties.setProperty("loginTimeout", "5");

        Map<String, Object> result = new LinkedHashMap<>();
        long started = System.nanoTime();
        try (Connection open = DriverManager.getConnection((String) connection.get("jdbc_url"), properties)) {
            String version = open.getMetaData().getDatabaseProductName() + " "
                    + open.getMetaData().getDatabaseProductVersion();
            result.put("ok", true);
            result.put("dbVersion", version);
            if (SqlServerAccess.isSqlServer((String) connection.get("jdbc_url")) && !SqlServerAccess.canViewDefinitions(open)) {
                result.put("warnings", java.util.List.of(SqlServerAccess.VIEW_DEFINITION_MISSING));
            }
            record(id, true, version);
        } catch (SQLException e) {
            result.put("ok", false);
            // The driver message can name the host and the login, but never the password.
            result.put("message", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            record(id, false, null);
        }
        result.put("latencyMs", (System.nanoTime() - started) / 1_000_000);
        // Only the outcome: the driver message can name the host, and the trail is not the place for it.
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("ok", result.get("ok"));
        outcome.put("latencyMs", result.get("latencyMs"));
        audit.record(workspace, AuditService.actorOf(authentication), "connection.test", "connection", id.toString(), null, outcome);
        return result;
    }

    // ----------------------------------------------------------------- helpers

    private void validate(ConnectionRequest body) {
        Validation.slug(body.slug(), "slug");
        Validation.required(body.displayName(), "displayName");
        Validation.required(body.engine(), "engine");
        Validation.required(body.jdbcUrl(), "jdbcUrl");
        if (body.environmentId() == null) {
            throw LqmException.invalid("environmentId is required");
        }
        if (JdbcFingerprint.hasCredentials(body.jdbcUrl())) {
            throw LqmException.invalid("jdbcUrl must not carry credentials; use a secret reference");
        }
        if (JdbcFingerprint.of(body.jdbcUrl()) == null) {
            throw LqmException.invalid("jdbcUrl is not a supported JDBC URL");
        }
        if (body.secretProvider() != null) {
            Validation.oneOf(body.secretProvider(), "secretProvider",
                    "NONE", "ENV", "VAULT", "CONSUL", "AZURE_KV");
        }
    }

    private Map<String, Object> commonParams(ConnectionRequest body, UUID workspace) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("ws", workspace);
        params.put("env", body.environmentId());
        params.put("slug", body.slug());
        params.put("name", body.displayName().trim());
        params.put("engine", body.engine().toUpperCase());
        params.put("url", body.jdbcUrl().trim());
        params.put("schema", body.defaultSchema());
        params.put("fp", JdbcFingerprint.of(body.jdbcUrl()));
        params.put("provider", body.secretProvider());
        params.put("ref", body.secretRef());
        params.put("drift", Boolean.TRUE.equals(body.driftEnabled()));
        params.put("cron", body.driftCron());
        params.put("scanner", body.scannerEnabled() == null || body.scannerEnabled());
        params.put("interval", body.scannerIntervalMin());
        params.put("verified", body.verified() == null || body.verified());
        return params;
    }

    private void linkProjects(UUID connectionId, List<UUID> projectIds) {
        if (projectIds == null) {
            return;
        }
        for (UUID projectId : projectIds) {
            jdbc.sql("insert into project_connection (project_id, db_connection_id) values (:p, :c) "
                            + "on conflict do nothing")
                    .param("p", projectId).param("c", connectionId).update();
        }
    }

    private void record(UUID id, boolean ok, String version) {
        jdbc.sql("update db_connection set last_test_at = now(), last_test_ok = :ok, "
                        + "db_version = coalesce(:v, db_version) where id = :id")
                .param("ok", ok).param("v", version).param("id", id).update();
    }

    private Map<String, Object> row(UUID workspace, UUID id) {
        List<Map<String, Object>> rows = jdbc.sql("select " + COLUMNS
                        + " from db_connection where id = :id and workspace_id = :ws")
                .param("id", id).param("ws", workspace).query().listOfRows();
        if (rows.isEmpty()) {
            throw LqmException.notFound("connection");
        }
        return rows.get(0);
    }

    private Map<String, Object> present(Map<String, Object> row) {
        Map<String, Object> out = Rows.camel(row);
        out.put("projectIds", jdbc.sql("select project_id from project_connection where db_connection_id = :id order by project_id")
                .param("id", row.get("id")).query(UUID.class).list());
        return out;
    }
}
