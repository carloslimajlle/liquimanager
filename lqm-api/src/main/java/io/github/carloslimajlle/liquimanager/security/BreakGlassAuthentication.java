package io.github.carloslimajlle.liquimanager.security;

import java.util.List;
import java.util.UUID;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Who a break-glass session is. The name carries a prefix a person of the identity provider cannot have, and
 * {@code PermissionService.identityOf} marks the caller as break-glass only for this class, never by the name, so nobody can
 * claim it with a username.
 */
public class BreakGlassAuthentication extends AbstractAuthenticationToken {

    private final String name;
    private final UUID sessionId;

    BreakGlassAuthentication(String username, UUID sessionId) {
        super(List.of());
        this.name = "break-glass:" + username;
        this.sessionId = sessionId;
        setAuthenticated(true);
    }

    public UUID sessionId() {
        return sessionId;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getCredentials() {
        return "";
    }

    @Override
    public Object getPrincipal() {
        return name;
    }
}
