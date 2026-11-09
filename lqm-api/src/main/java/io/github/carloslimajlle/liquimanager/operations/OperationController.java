package io.github.carloslimajlle.liquimanager.operations;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.access.ReadScope;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.ingest.BlobStore;

/**
 * Operations: the audit trail of everything Liquibase did (screens 05 and 02).
 *
 * <p>Not here yet, on purpose: {@code /operations/{id}/report} (HTML/PDF export) and
 * the rollback requests. Both are phase 2 (E17) and need the approval flow.
 */
@RestController
@RequestMapping(Api.V1 + "/operations")
class OperationController {

    private static final int MAX_PAGE_SIZE = 200;

    private final OperationQueries queries;
    private final WorkspaceContext workspaces;
    private final BlobStore blobs;
    private final ReadScope read;

    OperationController(OperationQueries queries, WorkspaceContext workspaces, BlobStore blobs, ReadScope read) {
        this.read = read;
        this.queries = queries;
        this.workspaces = workspaces;
        this.blobs = blobs;
    }

    @GetMapping
    OperationQueries.Page<Map<String, Object>> list(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            OperationFilter filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String sort, Authentication authentication) {
        // A page size is capped rather than rejected: a client asking for 10,000 rows
        // should get a usable page, and the cap protects the database either way.
        int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        UUID workspace = workspaces.resolve(ws);
        return queries.list(workspace, filter, Math.max(0, page), boundedSize, sort, read.of(workspace, authentication));
    }

    @GetMapping("/kpis")
    OperationQueries.Kpis kpis(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               OperationFilter filter, Authentication authentication) {
        UUID workspace = workspaces.resolve(ws);
        return queries.kpis(workspace, filter, Instant.now(), read.of(workspace, authentication));
    }

    @GetMapping("/{id}")
    Map<String, Object> get(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                            @PathVariable UUID id) {
        return queries.detail(workspaces.resolve(ws), id).orElseThrow(() -> LqmException.notFound("operation"));
    }

    @GetMapping("/{id}/changesets")
    OperationQueries.Page<Map<String, Object>> changesets(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        queries.detail(workspaces.resolve(ws), id).orElseThrow(() -> LqmException.notFound("operation"));
        return queries.changesets(id, Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE)));
    }

    @GetMapping(value = "/{id}/log", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> log(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               @PathVariable UUID id) {
        return blob(ws, id, "log");
    }

    @GetMapping(value = "/{id}/sql", produces = MediaType.TEXT_PLAIN_VALUE)
    ResponseEntity<String> sql(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               @PathVariable UUID id) {
        return blob(ws, id, "sql");
    }

    private ResponseEntity<String> blob(String ws, UUID id, String kind) {
        // An absent column and a missing operation both come back empty from the query;
        // either way the answer is the same 404, worded for what the caller asked for.
        return queries.blobReference(workspaces.resolve(ws), id, kind)
                .flatMap(blobs::get)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> LqmException.notFound("the " + kind + " of this operation"));
    }
}
