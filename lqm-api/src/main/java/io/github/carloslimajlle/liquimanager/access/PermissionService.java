package io.github.carloslimajlle.liquimanager.access;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

/**
 * Answers "may this caller do this here?" from the role bindings (docs/09).
 *
 * <p>Who is calling comes from {@link Identity}. There are three kinds: a JWT (the real thing),
 * a named development user (the {@code X-LQM-User} header, which goes through the same rules as
 * a real one), and the unnamed development caller, which has no identity at all and is let
 * through — that last one only exists when no identity provider is configured, because the
 * development chain is the only place a request can arrive without any authentication.
 *
 * <p>{@code lqm.security.bootstrap-admins} names callers who hold every permission regardless of
 * bindings. It exists so a fresh install has someone who can grant the first binding; without it
 * enforcement would lock everybody out of a workspace nobody has access to yet.
 */
@Service
public class PermissionService {

    /**
     * The caller. {@code open} is the unnamed development caller; {@code breakGlass} is the emergency account (docs/09 § 1), which holds
     * every permission and is recognised by how it signed in, never by its name.
     */
    public record Identity(String name, String email, List<String> groups, boolean open, boolean breakGlass) {

        public Identity(String name, String email, List<String> groups, boolean open) {
            this(name, email, groups, open, false);
        }

        public List<String> externalIds() {
            List<String> ids = new ArrayList<>(groups);
            if (name != null) {
                ids.add(name);
            }
            if (email != null) {
                ids.add(email);
            }
            return ids;
        }
    }

    /** The permission every read needs; a read of a listed resource is judged in the scope of that resource. */
    public static final String READ = "assignment:view";

    /** One binding as it matters for a decision. */
    public record Grant(UUID id, String scopeType, UUID scopeId, List<String> permissions, List<String> denied) {
    }

    private final JdbcClient jdbc;
    private final Set<String> bootstrapAdmins;

    PermissionService(JdbcClient jdbc, @Value("${lqm.security.bootstrap-admins:}") List<String> bootstrapAdmins) {
        this.jdbc = jdbc;
        this.bootstrapAdmins = Set.copyOf(bootstrapAdmins.stream().map(String::trim).filter(s -> !s.isEmpty()).toList());
    }

    public static Identity identityOf(Authentication authentication) {
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            return new Identity("dev", null, List.of(), true);
        }
        if (authentication instanceof io.github.carloslimajlle.liquimanager.security.BreakGlassAuthentication) {
            return new Identity(authentication.getName(), null, List.of(), false, true);
        }
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String preferred = jwt.getToken().getClaimAsString("preferred_username");
            List<String> groups = jwt.getToken().getClaimAsStringList("groups");
            return new Identity(preferred != null ? preferred : jwt.getName(),
                    jwt.getToken().getClaimAsString("email"), groups == null ? List.of() : groups, false);
        }
        return new Identity(authentication.getName(), null, List.of(), false);
    }

    /** Holds every permission regardless of bindings: a bootstrap admin, or the break-glass account. */
    public boolean isBootstrapAdmin(Identity who) {
        return who.breakGlass() || who.externalIds().stream().anyMatch(bootstrapAdmins::contains);
    }

    public List<Grant> grantsOf(UUID workspace, Identity who) {
        List<String> ids = who.externalIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                select b.id, b.scope_type, b.scope_id, b.extra_permissions, b.denied_permissions, rt.permissions
                  from role_binding b
                  join principal p on p.id = b.principal_id
                  left join role_template rt on rt.id = b.role_template_id
                 where b.workspace_id = :ws and p.external_id in (:ids)
                   and (b.expires_at is null or b.expires_at > now())
                """).param("ws", workspace).param("ids", ids)
                .query((rs, n) -> {
                    List<String> template = strings(rs.getArray("permissions"));
                    List<String> extra = strings(rs.getArray("extra_permissions"));
                    List<String> denied = strings(rs.getArray("denied_permissions"));
                    return new Grant(rs.getObject("id", UUID.class), rs.getString("scope_type"),
                            rs.getObject("scope_id", UUID.class), Permissions.effective(template, extra, denied), denied);
                }).list();
    }

    /** {@link #allowed(UUID, Identity, String, ScopeTarget)} for an action that is about one project, or none. */
    public boolean allowed(UUID workspace, Identity who, String permission, Optional<UUID> project) {
        return allowed(workspace, who, permission, project.map(ScopeTarget::ofProject).orElse(ScopeTarget.nothing()));
    }

    /**
     * Allowed by a binding that applies to what the action touches: the whole workspace, or a project, connection,
     * pipeline or environment the action is about ({@link ScopeTarget}). A binding on one of them says nothing about
     * another, and a denial in scope beats every grant.
     */
    public boolean allowed(UUID workspace, Identity who, String permission, ScopeTarget target) {
        if (who.open() || isBootstrapAdmin(who)) {
            return true;
        }
        return allowedBy(grantsOf(workspace, who), permission, target);
    }

    /** The decision itself, for callers that already hold the grants (a list evaluates many resources against one set). */
    public static boolean allowedBy(List<Grant> grants, String permission, ScopeTarget target) {
        List<String> granted = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        for (Grant grant : grants) {
            if (target.covers(grant.scopeType(), grant.scopeId())) {
                granted.addAll(grant.permissions());
                denied.addAll(grant.denied());
            }
        }
        return Permissions.allowed(granted, denied, permission);
    }

    /**
     * "Somewhere": granted by a binding in ANY scope, not denied in any. This is what plain reads need, since a
     * list does not know which scope each of its rows belongs to.
     */
    public boolean allowedSomewhere(UUID workspace, Identity who, String permission) {
        if (who.open() || isBootstrapAdmin(who)) {
            return true;
        }
        List<String> granted = new ArrayList<>();
        List<String> denied = new ArrayList<>();
        for (Grant grant : grantsOf(workspace, who)) {
            granted.addAll(grant.permissions());
            denied.addAll(grant.denied());
        }
        return Permissions.allowed(granted, denied, permission);
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }
}
