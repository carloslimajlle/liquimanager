package io.github.carloslimajlle.liquimanager.security;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code POST /auth/break-glass}: the sign-in of the emergency account, and {@code .../logout} to end the session. Without the feature
 * enabled both answer 404, as if they did not exist. Every sign-in and every refusal is audited; a refusal says only that the
 * credentials were not accepted, never which of the three was wrong (LQM-080), and a locked account says so (LQM-081, 429).
 */
@RestController
@RequestMapping(Api.V1 + "/auth/break-glass")
class BreakGlassController {

    record SignIn(String username, String password, String code) {
    }

    private final BreakGlassService service;
    private final AuditService audit;
    private final WorkspaceContext workspaces;

    BreakGlassController(BreakGlassService service, AuditService audit, WorkspaceContext workspaces) {
        this.service = service;
        this.audit = audit;
        this.workspaces = workspaces;
    }

    @PostMapping
    Map<String, Object> signIn(@RequestBody SignIn body, HttpServletRequest request) {
        if (!service.enabled()) {
            throw LqmException.notFound("break-glass");
        }
        UUID workspace = workspaces.resolve(null);
        String ip = request.getRemoteAddr();
        BreakGlassService.Attempt attempt = service.signIn(body.username(), body.password(), body.code(), ip, Instant.now());
        switch (attempt.outcome()) {
            case SIGNED_IN -> {
                audit.record(workspace, "break-glass:" + service.username(), "auth.break-glass.login", "break-glass", service.username(),
                        null, Map.of("ip", String.valueOf(ip), "expiresAt", attempt.issued().expiresAt().toString()));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("token", attempt.issued().token());
                out.put("expiresAt", attempt.issued().expiresAt());
                return out;
            }
            case LOCKED -> {
                audit.record(workspace, "anonymous", "auth.break-glass.locked", "break-glass", service.username(), null,
                        Map.of("ip", String.valueOf(ip)));
                throw new LqmException(HttpStatus.TOO_MANY_REQUESTS, "LQM-081", "too many attempts: the account is locked for a while");
            }
            default -> {
                audit.record(workspace, "anonymous", "auth.break-glass.failed", "break-glass", service.username(), null,
                        Map.of("ip", String.valueOf(ip)));
                throw new LqmException(HttpStatus.UNAUTHORIZED, "LQM-080", "credentials not accepted");
            }
        }
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization, Authentication authentication) {
        if (!service.enabled() || !(authentication instanceof BreakGlassAuthentication session) || authorization == null) {
            throw LqmException.notFound("break-glass");
        }
        service.revoke(authorization.substring("Bearer ".length()).trim());
        audit.record(workspaces.resolve(null), session.getName(), "auth.break-glass.logout", "break-glass", service.username(), null, Map.of());
        return ResponseEntity.noContent().build();
    }
}
