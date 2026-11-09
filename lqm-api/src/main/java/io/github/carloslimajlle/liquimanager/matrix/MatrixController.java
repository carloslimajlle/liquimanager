package io.github.carloslimajlle.liquimanager.matrix;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;

/**
 * {@code GET /pipelines/{id}/matrix}: changeset by environment (screen 04).
 *
 * <p>Not here yet, on purpose: the {@code /timeline} view and the {@code promote} action
 * are E23 (phase 3). Promoting needs the GitLab trigger and the {@code promote:<env>}
 * permission, neither of which exists yet.
 */
@RestController
@RequestMapping(Api.V1 + "/pipelines/{id}/matrix")
class MatrixController {

    private static final int MAX_PAGE_SIZE = 500;

    private final MatrixService matrix;
    private final WorkspaceContext workspaces;

    MatrixController(MatrixService matrix, WorkspaceContext workspaces) {
        this.matrix = matrix;
        this.workspaces = workspaces;
    }

    @GetMapping
    Map<String, Object> get(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                            @PathVariable UUID id,
                            @RequestParam(required = false) String release,
                            @RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "100") int size) {
        // The matrix is read as a whole grid, so the page cap is higher than for lists.
        return matrix.matrix(workspaces.resolve(ws), id, new MatrixService.Filter(release, q),
                Math.max(0, page), Math.max(1, Math.min(size, MAX_PAGE_SIZE)));
    }
}
