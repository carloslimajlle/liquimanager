package io.github.carloslimajlle.liquimanager.access;

import java.util.Set;
import java.util.UUID;

/**
 * What an action touches, in the terms a binding is scoped by (docs/09 § 2): a binding on PROJECT, CONNECTION, PIPELINE or
 * ENVIRONMENT applies to an action when that action's target contains the binding's scope id.
 *
 * <p>Each set holds the ids of that kind the action is ABOUT, never more: a promotion to PRD touches the PRD environment and not
 * the other environments of the same pipeline, or a permission granted for DEV would be good for PRD.
 */
public record ScopeTarget(Set<UUID> projects, Set<UUID> connections, Set<UUID> pipelines, Set<UUID> environments) {

    private static final ScopeTarget NOTHING = new ScopeTarget(Set.of(), Set.of(), Set.of(), Set.of());

    public ScopeTarget {
        projects = clean(projects);
        connections = clean(connections);
        pipelines = clean(pipelines);
        environments = clean(environments);
    }

    /** An immutable copy without nulls: a pipeline may have no project, and a missing id is not a scope. */
    private static Set<UUID> clean(Set<UUID> ids) {
        return ids.stream().filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** An action that is about no particular resource: only a workspace-wide binding applies. */
    public static ScopeTarget nothing() {
        return NOTHING;
    }

    /** True when the action is about no particular resource. */
    public boolean isEmpty() {
        return projects.isEmpty() && connections.isEmpty() && pipelines.isEmpty() && environments.isEmpty();
    }

    public static ScopeTarget ofProject(UUID project) {
        return project == null ? NOTHING : new ScopeTarget(Set.of(project), Set.of(), Set.of(), Set.of());
    }

    /** Everything either of them is about. */
    public ScopeTarget union(ScopeTarget other) {
        Set<UUID> p = new java.util.HashSet<>(projects);
        Set<UUID> c = new java.util.HashSet<>(connections);
        Set<UUID> l = new java.util.HashSet<>(pipelines);
        Set<UUID> e = new java.util.HashSet<>(environments);
        p.addAll(other.projects);
        c.addAll(other.connections);
        l.addAll(other.pipelines);
        e.addAll(other.environments);
        return new ScopeTarget(p, c, l, e);
    }

    /** Does a binding of this scope type and id apply to the action? A WORKSPACE binding always does. */
    public boolean covers(String scopeType, UUID scopeId) {
        return switch (scopeType) {
            case "WORKSPACE" -> true;
            case "PROJECT" -> scopeId != null && projects.contains(scopeId);
            case "CONNECTION" -> scopeId != null && connections.contains(scopeId);
            case "PIPELINE" -> scopeId != null && pipelines.contains(scopeId);
            case "ENVIRONMENT" -> scopeId != null && environments.contains(scopeId);
            default -> false;
        };
    }
}
