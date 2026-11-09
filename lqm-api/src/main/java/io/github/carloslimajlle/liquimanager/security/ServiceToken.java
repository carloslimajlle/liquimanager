package io.github.carloslimajlle.liquimanager.security;

import java.util.List;
import java.util.UUID;

/** An authenticated service token: who it belongs to and what it may do. */
public record ServiceToken(UUID id, UUID workspaceId, String name, List<String> scopes, List<UUID> projectIds) {

    public boolean hasScope(String scope) {
        return scopes.contains(scope);
    }
}
