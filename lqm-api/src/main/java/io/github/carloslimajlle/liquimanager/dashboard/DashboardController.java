package io.github.carloslimajlle.liquimanager.dashboard;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import org.springframework.security.core.Authentication;
import io.github.carloslimajlle.liquimanager.access.ReadScope;

/**
 * {@code GET /dashboard} and {@code GET /dashboard/activity} (screen 01).
 *
 * <p>The live feed itself is {@code /stream/operations}; this serves the first load and
 * the refreshes the screen makes when an event arrives.
 */
@RestController
@RequestMapping(Api.V1 + "/dashboard")
class DashboardController {

    private static final int MAX_ACTIVITY = 50;

    private final DashboardService dashboard;
    private final WorkspaceContext workspaces;
    private final ReadScope read;

    DashboardController(DashboardService dashboard, WorkspaceContext workspaces, ReadScope read) {
        this.read = read;
        this.dashboard = dashboard;
        this.workspaces = workspaces;
    }

    @GetMapping
    Map<String, Object> get(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                            @RequestParam(defaultValue = "7d") String period, Authentication authentication) {
        UUID workspace = workspaces.resolve(ws);
        return dashboard.dashboard(workspace, period, Instant.now(), read.of(workspace, authentication));
    }

    @GetMapping("/activity")
    List<Map<String, Object>> activity(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                       @RequestParam(defaultValue = "9") int limit,
                                       @RequestParam(required = false) java.util.UUID project, Authentication authentication) {
        java.util.UUID workspace = workspaces.resolve(ws);
        return dashboard.activity(workspace, Math.max(1, Math.min(limit, MAX_ACTIVITY)), project, read.of(workspace, authentication));
    }
}
