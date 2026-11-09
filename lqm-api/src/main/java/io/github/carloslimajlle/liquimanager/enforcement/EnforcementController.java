package io.github.carloslimajlle.liquimanager.enforcement;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.access.ReadScope;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import org.springframework.security.core.Authentication;

/** {@code GET /enforcement}: the gates of a pipeline and what they found (screen 06). */
@RestController
@RequestMapping(Api.V1 + "/enforcement")
class EnforcementController {

    private final EnforcementService enforcement;
    private final WorkspaceContext workspaces;
    private final ReadScope read;

    EnforcementController(EnforcementService enforcement, WorkspaceContext workspaces, ReadScope read) {
        this.enforcement = enforcement;
        this.workspaces = workspaces;
        this.read = read;
    }

    @GetMapping
    Map<String, Object> get(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                            @RequestParam UUID pipeline,
                            @RequestParam(defaultValue = "7d") String period, Authentication authentication) {
        UUID workspace = workspaces.resolve(ws);
        // The pipeline is a query parameter, not part of the path, so the interceptor cannot judge it: judged here, in its own scope.
        if (!read.of(workspace, authentication).pipeline(pipeline)) {
            throw LqmException.forbidden("no access to this pipeline");
        }
        return enforcement.enforcement(workspace, pipeline, period, Instant.now());
    }
}
