package io.github.carloslimajlle.liquimanager.catalog;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.security.ServiceTokenService;

/**
 * Service tokens for collectors (RF-ADM-03). The plaintext token is returned exactly
 * once, by the POST that creates it; afterwards only the visible prefix remains.
 */
@RestController
@RequestMapping(Api.V1 + "/service-tokens")
class ServiceTokenController {

    private static final List<String> KNOWN_SCOPES = List.of("ingest", "checks:read", "runner", "webhook");

    private final ServiceTokenService tokens;
    private final WorkspaceContext workspaces;
    private final AuditService audit;

    ServiceTokenController(ServiceTokenService tokens, WorkspaceContext workspaces, AuditService audit) {
        this.audit = audit;
        this.tokens = tokens;
        this.workspaces = workspaces;
    }

    record TokenRequest(String name, List<String> scopes, List<UUID> projectIds, Instant expiresAt) {
    }

    @GetMapping
    List<ServiceTokenService.Summary> list(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws) {
        return tokens.list(workspaces.resolve(ws));
    }

    @PostMapping
    ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
            Authentication authentication, @RequestBody TokenRequest body) {
        UUID workspace = workspaces.resolve(ws);
        Validation.required(body.name(), "name");
        if (body.scopes() != null) {
            for (String scope : body.scopes()) {
                if (!KNOWN_SCOPES.contains(scope)) {
                    throw LqmException.invalid("unknown scope: " + scope);
                }
            }
        }
        ServiceTokenService.Issued issued = tokens.issue(workspace, body.name().trim(), body.scopes(),
                body.projectIds(), body.expiresAt(), authentication == null ? "unknown" : authentication.getName());

        // The audit trail never carries the token itself: who made it, its name, scopes and projects.
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", body.name().trim());
        summary.put("prefix", issued.prefix());
        summary.put("scopes", body.scopes());
        summary.put("projectIds", body.projectIds());
        summary.put("expiresAt", body.expiresAt() == null ? null : body.expiresAt().toString());
        audit.record(workspace, AuditService.actorOf(authentication), "token.create", "service-token", issued.id().toString(), null, summary);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", issued.id());
        response.put("prefix", issued.prefix());
        response.put("token", issued.token());
        response.put("notice", "This is the only time the token is shown. Store it now.");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> revoke(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                Authentication authentication, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        if (!tokens.revoke(workspace, id)) {
            throw LqmException.notFound("service token");
        }
        audit.record(workspace, AuditService.actorOf(authentication), "token.revoke", "service-token", id.toString(), null, null);
        return ResponseEntity.noContent().build();
    }
}
