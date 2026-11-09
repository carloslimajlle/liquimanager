package io.github.carloslimajlle.liquimanager.access;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * What a caller may READ, in the same terms a write is decided by (docs/09 § 8.2): a binding on a project, connection, pipeline or
 * environment lets its holder see that resource and what hangs off it, and nothing else; a workspace-wide binding sees all.
 *
 * <p>The rule for one resource is the one {@link ScopeResolver} uses for a request about it ({@code ScopeTarget} of a project, of a
 * connection, of a pipeline), so a list and the detail page of the same row never disagree. The lists ask once for a {@link Visible}
 * and add its SQL fragment; the interceptor asks the same question for a request with an id in the path.
 *
 * <p>Whoever has no identity (development without an identity provider) and the bootstrap admins see everything.
 */
@Component
public class ReadScope {

    /** What the ids can be compared with when nothing is visible: {@code in ()} is not valid SQL. */
    private static final UUID NOTHING = new UUID(0, 0);

    /** A SQL fragment and its named parameters. */
    public record Filter(String sql, Map<String, Object> params) {
        public static final Filter NONE = new Filter("", Map.of());
    }

    /** The ids of the projects, connections and pipelines the caller may read; {@code all} when there is no limit. */
    public record Visible(boolean all, Set<UUID> projects, Set<UUID> connections, Set<UUID> pipelines) {

        public static Visible everything() {
            return new Visible(true, Set.of(), Set.of(), Set.of());
        }

        public boolean project(UUID id) {
            return all || projects.contains(id);
        }

        public boolean connection(UUID id) {
            return all || connections.contains(id);
        }

        public boolean pipeline(UUID id) {
            return all || pipelines.contains(id);
        }

        /** {@code " and <column> in (...)"}, or nothing when everything is visible. */
        public Filter projects(String column, String key) {
            return in(column, key, projects);
        }

        public Filter connections(String column, String key) {
            return in(column, key, connections);
        }

        public Filter pipelines(String column, String key) {
            return in(column, key, pipelines);
        }

        private Filter in(String column, String key, Set<UUID> ids) {
            if (all) {
                return Filter.NONE;
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put(key, ids.isEmpty() ? List.of(NOTHING) : new ArrayList<>(ids));
            return new Filter(" and " + column + " in (:" + key + ")", params);
        }
    }

    private final JdbcClient jdbc;
    private final PermissionService permissions;

    ReadScope(JdbcClient jdbc, PermissionService permissions) {
        this.jdbc = jdbc;
        this.permissions = permissions;
    }

    public Visible of(UUID workspace, Authentication authentication) {
        PermissionService.Identity who = PermissionService.identityOf(authentication);
        if (who.open() || permissions.isBootstrapAdmin(who)) {
            return Visible.everything();
        }
        List<PermissionService.Grant> grants = permissions.grantsOf(workspace, who);
        boolean workspaceWide = grants.stream().anyMatch(g -> "WORKSPACE".equals(g.scopeType()));
        boolean anyScopedDenial = grants.stream().anyMatch(g -> !"WORKSPACE".equals(g.scopeType()) && !g.denied().isEmpty());
        if (workspaceWide && !anyScopedDenial && PermissionService.allowedBy(grants, PermissionService.READ, ScopeTarget.nothing())) {
            return Visible.everything();
        }
        return enumerate(workspace, grants);
    }

    /** Evaluates each project, connection and pipeline of the workspace against the grants, with the target the resolver gives it. */
    private Visible enumerate(UUID workspace, List<PermissionService.Grant> grants) {
        Map<UUID, UUID> environmentOf = new HashMap<>();
        for (Map<String, Object> row : jdbc.sql("select id, environment_id from db_connection where workspace_id = :ws and archived_at is null")
                .param("ws", workspace).query().listOfRows()) {
            environmentOf.put((UUID) row.get("id"), (UUID) row.get("environment_id"));
        }
        Map<UUID, Set<UUID>> projectsOf = new HashMap<>();
        Map<UUID, Set<UUID>> pipelinesOf = new HashMap<>();
        for (Map<String, Object> row : jdbc.sql("select pc.db_connection_id, pc.project_id from project_connection pc join project p on p.id = pc.project_id where p.workspace_id = :ws")
                .param("ws", workspace).query().listOfRows()) {
            projectsOf.computeIfAbsent((UUID) row.get("db_connection_id"), k -> new HashSet<>()).add((UUID) row.get("project_id"));
        }
        for (Map<String, Object> row : jdbc.sql("""
                select ps.db_connection_id, pl.id as pipeline_id, pl.project_id
                  from pipeline_stage ps join pipeline pl on pl.id = ps.pipeline_id where pl.workspace_id = :ws
                """).param("ws", workspace).query().listOfRows()) {
            UUID connection = (UUID) row.get("db_connection_id");
            pipelinesOf.computeIfAbsent(connection, k -> new HashSet<>()).add((UUID) row.get("pipeline_id"));
            if (row.get("project_id") != null) {
                projectsOf.computeIfAbsent(connection, k -> new HashSet<>()).add((UUID) row.get("project_id"));
            }
        }

        Set<UUID> projects = new HashSet<>();
        for (UUID id : jdbc.sql("select id from project where workspace_id = :ws and archived_at is null").param("ws", workspace)
                .query(UUID.class).list()) {
            if (PermissionService.allowedBy(grants, PermissionService.READ, ScopeTarget.ofProject(id))) {
                projects.add(id);
            }
        }
        Set<UUID> connections = new HashSet<>();
        for (Map.Entry<UUID, UUID> connection : environmentOf.entrySet()) {
            ScopeTarget target = new ScopeTarget(projectsOf.getOrDefault(connection.getKey(), Set.of()), Set.of(connection.getKey()),
                    pipelinesOf.getOrDefault(connection.getKey(), Set.of()), Set.of(connection.getValue()));
            if (PermissionService.allowedBy(grants, PermissionService.READ, target)) {
                connections.add(connection.getKey());
            }
        }
        Set<UUID> pipelines = new HashSet<>();
        for (Map<String, Object> row : jdbc.sql("select id, project_id from pipeline where workspace_id = :ws").param("ws", workspace)
                .query().listOfRows()) {
            UUID project = (UUID) row.get("project_id");
            ScopeTarget target = new ScopeTarget(project == null ? Set.of() : Set.of(project), Set.of(), Set.of((UUID) row.get("id")), Set.of());
            if (PermissionService.allowedBy(grants, PermissionService.READ, target)) {
                pipelines.add((UUID) row.get("id"));
            }
        }
        return new Visible(false, projects, connections, pipelines);
    }
}
