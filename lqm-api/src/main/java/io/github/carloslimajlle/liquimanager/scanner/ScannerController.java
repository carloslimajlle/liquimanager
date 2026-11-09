package io.github.carloslimajlle.liquimanager.scanner;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;

/** Scanner status of a connection, and a scan on demand. */
@RestController
@RequestMapping(Api.V1 + "/connections/{id}")
class ScannerController {

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final ScannerService scanner;

    ScannerController(JdbcClient jdbc, WorkspaceContext workspaces, ScannerService scanner) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.scanner = scanner;
    }

    @GetMapping("/scanner")
    Map<String, Object> status(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        var connection = jdbc.sql("select scanner_enabled, scanner_interval_min from db_connection where id = :id and workspace_id = :ws")
                .param("id", id).param("ws", workspace).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("connection"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", connection.get("scanner_enabled"));
        out.put("intervalMinutes", connection.get("scanner_interval_min"));
        jdbc.sql("select last_seen, last_run_at, last_ok, last_error, stuck_lock_since from scanner_state where db_connection_id = :id")
                .param("id", id).query().listOfRows().stream().findFirst().ifPresent(s -> {
                    out.put("lastRunAt", instant(s.get("last_run_at")));
                    out.put("lastOk", s.get("last_ok"));
                    out.put("lastError", s.get("last_error"));
                    out.put("lastSeen", instant(s.get("last_seen")));
                    out.put("stuckLockSince", instant(s.get("stuck_lock_since")));
                });
        return out;
    }

    @PostMapping("/scan")
    Map<String, Object> scan(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID id) {
        ScannerService.Result result = scanner.scan(workspaces.resolve(ws), id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", result.ok());
        out.put("deployments", result.deployments());
        out.put("outOfProcess", result.outOfProcess());
        out.put("firstPass", result.firstPass());
        out.put("stuckLockSince", result.stuckLockSince());
        out.put("error", result.error());
        return out;
    }

    private static Object instant(Object timestamp) {
        return timestamp == null ? null : ((Timestamp) timestamp).toInstant();
    }
}
