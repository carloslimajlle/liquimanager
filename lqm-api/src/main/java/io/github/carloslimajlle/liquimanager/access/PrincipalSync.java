package io.github.carloslimajlle.liquimanager.access;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * Keeps the {@code principal} table in step with who actually signs in (D-03): the caller and each group of the
 * token become principals the first time they are seen, so a group shows up in the access screen's picker without
 * anybody typing it, and an administrator can grant it a role.
 *
 * <p>It does NOT grant anything: a new principal has no binding, so a new group still sees nothing until somebody binds
 * it (least privilege). It only removes the step where the administrator had to know the group's name and register it
 * by hand.
 *
 * <p>The sync is throttled per caller: a person's token changes rarely, and an upsert on every request would be a write
 * per page load. Only the creation of a principal is audited; refreshing a name is not an event.
 */
@Service
public class PrincipalSync {

    private static final Duration EVERY = Duration.ofMinutes(5);
    /** More than this many identities in the cache means something is minting names; start over rather than grow. */
    private static final int MAX_CACHED = 10_000;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final WorkspaceContext workspaces;
    private final Map<String, Instant> seen = new ConcurrentHashMap<>();

    PrincipalSync(JdbcClient jdbc, AuditService audit, WorkspaceContext workspaces) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.workspaces = workspaces;
    }

    /** Registers the caller and its groups, unless that was done for the same caller and groups a moment ago. */
    public void sync(Authentication authentication, String workspaceSlug) {
        // Only people the identity provider vouched for. The development chain's X-LQM-User header names anybody.
        if (!(authentication instanceof JwtAuthenticationToken)) {
            return;
        }
        PermissionService.Identity who = PermissionService.identityOf(authentication);
        if (who.name() == null || who.name().isBlank()) {
            return;
        }
        String key = who.name() + '\u0000' + String.join("\u0000", who.groups()) + '\u0000' + who.email();
        Instant now = Instant.now();
        Instant last = seen.get(key);
        if (last != null && Duration.between(last, now).compareTo(EVERY) < 0) {
            return;
        }
        if (seen.size() > MAX_CACHED) {
            seen.clear();
        }

        UUID workspace;
        try {
            workspace = workspaces.resolve(workspaceSlug);
        } catch (LqmException unknownWorkspace) {
            // The handler answers 404 for a workspace that does not exist; there is nothing to register it under.
            return;
        }
        String display = displayNameOf(authentication, who);
        upsert(workspace, who.name(), "USER", display, who.email(), who.name());
        for (String group : who.groups()) {
            if (group != null && !group.isBlank()) {
                upsert(workspace, group.trim(), "GROUP", group.trim(), null, who.name());
            }
        }
        seen.put(key, now);
    }

    private static String displayNameOf(Authentication authentication, PermissionService.Identity who) {
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String name = jwt.getToken().getClaimAsString("name");
            if (name != null && !name.isBlank()) {
                return name.trim();
            }
        }
        return who.name();
    }

    /**
     * Inserts the principal, or refreshes the name and e-mail of the one that exists; its type is never changed, so a
     * principal an administrator registered by hand keeps what they chose. Only a new row is audited.
     */
    private void upsert(UUID workspace, String externalId, String type, String displayName, String email, String actor) {
        boolean inserted = Boolean.TRUE.equals(jdbc.sql("""
                insert into principal (id, type, external_id, display_name, email)
                values (:id, :type, :ext, :name, :email)
                on conflict (external_id) do update
                   set display_name = excluded.display_name, email = coalesce(excluded.email, principal.email)
                returning (xmax = 0)
                """).param("id", UuidV7.next()).param("type", type).param("ext", externalId)
                .param("name", displayName).param("email", email).query(Boolean.class).single());
        if (inserted) {
            Map<String, Object> created = Map.of("type", type, "externalId", externalId, "displayName", displayName);
            audit.record(workspace, actor, "access.principal.sync", "principal", externalId, null, created);
        }
    }
}
