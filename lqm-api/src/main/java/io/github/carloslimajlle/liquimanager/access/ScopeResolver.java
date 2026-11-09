package io.github.carloslimajlle.liquimanager.access;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Turns "this request is about that resource" into the {@link ScopeTarget} the permission rules use.
 *
 * <p>The relationships it follows are the ones the catalogue has: a connection lives in an environment and is linked to
 * projects and used by pipeline stages; a pipeline belongs to a project; a stage is one environment and one connection of a
 * pipeline. Anything it does not recognise is about nothing in particular, so only a workspace-wide binding applies (the strict
 * answer).
 */
@Component
public class ScopeResolver {

    private static final String ID = "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})";
    private static final Pattern PROJECT = Pattern.compile("^/projects/" + ID + "(/.*)?$");
    private static final Pattern CONNECTION = Pattern.compile("^/connections/" + ID + "(/.*)?$");
    private static final Pattern STAGE = Pattern.compile("^/pipelines/" + ID + "/stages/" + ID + "(/.*)?$");
    private static final Pattern PIPELINE = Pattern.compile("^/pipelines/" + ID + "(/.*)?$");
    private static final Pattern PROMOTION = Pattern.compile("^/promotions/" + ID + "(/.*)?$");
    private static final Pattern ROLLBACK_REQUEST = Pattern.compile("^/rollback-requests/" + ID + "(/.*)?$");
    private static final Pattern OPERATION = Pattern.compile("^/operations/" + ID + "(/.*)?$");
    private static final Pattern DRIFT_RUN = Pattern.compile("^/drift/runs/" + ID + "(/.*)?$");
    private static final Pattern DRIFT_ITEM = Pattern.compile("^/drift/items/" + ID + "(/.*)?$");
    private static final Pattern EXCEPTION = Pattern.compile("^/policy-exceptions/" + ID + "(/.*)?$");
    private static final Pattern CHANGESET = Pattern.compile("^/changesets/" + ID + "(/.*)?$");
    private static final Pattern ENVIRONMENT = Pattern.compile("^/environments/" + ID + "(/.*)?$");

    private final JdbcClient jdbc;

    ScopeResolver(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The target of a request to {@code path} (relative to /api/v1). */
    public ScopeTarget forPath(String path) {
        Matcher m;
        if ((m = PROJECT.matcher(path)).matches()) {
            return ScopeTarget.ofProject(UUID.fromString(m.group(1)));
        }
        if ((m = CONNECTION.matcher(path)).matches()) {
            return forConnection(UUID.fromString(m.group(1)));
        }
        if ((m = STAGE.matcher(path)).matches()) {
            return forStage(UUID.fromString(m.group(1)), UUID.fromString(m.group(2)));
        }
        if ((m = PIPELINE.matcher(path)).matches()) {
            return forPipeline(UUID.fromString(m.group(1)));
        }
        if ((m = PROMOTION.matcher(path)).matches()) {
            return forPromotion(UUID.fromString(m.group(1)));
        }
        if ((m = ROLLBACK_REQUEST.matcher(path)).matches()) {
            return forConnection(connectionOf("select o.db_connection_id from rollback_request r join operation o on o.id = r.operation_id where r.id = :id",
                    UUID.fromString(m.group(1))));
        }
        if ((m = OPERATION.matcher(path)).matches()) {
            return forConnection(connectionOf("select db_connection_id from operation where id = :id", UUID.fromString(m.group(1))));
        }
        if ((m = DRIFT_RUN.matcher(path)).matches()) {
            return forConnection(connectionOf("select db_connection_id from drift_run where id = :id", UUID.fromString(m.group(1))));
        }
        if ((m = DRIFT_ITEM.matcher(path)).matches()) {
            return forConnection(connectionOf("select r.db_connection_id from drift_item i join drift_run r on r.id = i.drift_run_id where i.id = :id",
                    UUID.fromString(m.group(1))));
        }
        if ((m = EXCEPTION.matcher(path)).matches()) {
            // An exception about a project belongs to that project's scope; one about none is the workspace's (only a workspace-wide
            // binding decides it, and anybody who reads reads it).
            return ScopeTarget.ofProject(jdbc.sql("select project_id from policy_exception where id = :id")
                    .param("id", UUID.fromString(m.group(1))).query(UUID.class).optional().orElse(null));
        }
        if ((m = CHANGESET.matcher(path)).matches()) {
            // A changeset belongs to the project of its changelog.
            return ScopeTarget.ofProject(jdbc.sql("select cl.project_id from changeset cs join changelog cl on cl.id = cs.changelog_id where cs.id = :id")
                    .param("id", UUID.fromString(m.group(1))).query(UUID.class).optional().orElse(null));
        }
        if ((m = ENVIRONMENT.matcher(path)).matches()) {
            return new ScopeTarget(Set.of(), Set.of(), Set.of(), Set.of(UUID.fromString(m.group(1))));
        }
        return ScopeTarget.nothing();
    }

    /** A connection: itself, its environment, the projects linked to it and the pipelines that deploy to it. */
    public ScopeTarget forConnection(UUID connection) {
        if (connection == null) {
            return ScopeTarget.nothing();
        }
        Set<UUID> environments = new HashSet<>();
        Set<UUID> projects = new HashSet<>();
        Set<UUID> pipelines = new HashSet<>();
        jdbc.sql("select environment_id from db_connection where id = :id").param("id", connection)
                .query(UUID.class).list().forEach(environments::add);
        jdbc.sql("select project_id from project_connection where db_connection_id = :id").param("id", connection)
                .query(UUID.class).list().forEach(projects::add);
        for (Map<String, Object> row : jdbc.sql("""
                select pl.id, pl.project_id from pipeline_stage ps join pipeline pl on pl.id = ps.pipeline_id
                 where ps.db_connection_id = :id
                """).param("id", connection).query().listOfRows()) {
            pipelines.add((UUID) row.get("id"));
            projects.add((UUID) row.get("project_id"));
        }
        return new ScopeTarget(projects, Set.of(connection), pipelines, environments);
    }

    /** A pipeline as a whole: itself and its project. Its stages' environments and connections are NOT part of it. */
    public ScopeTarget forPipeline(UUID pipeline) {
        Set<UUID> projects = new HashSet<>(jdbc.sql("select project_id from pipeline where id = :id").param("id", pipeline)
                .query(UUID.class).list());
        return new ScopeTarget(projects, Set.of(), Set.of(pipeline), Set.of());
    }

    /** One stage: its pipeline and project, and that stage's own environment and connection. */
    public ScopeTarget forStage(UUID pipeline, UUID stage) {
        Set<UUID> projects = new HashSet<>();
        Set<UUID> connections = new HashSet<>();
        Set<UUID> environments = new HashSet<>();
        for (Map<String, Object> row : jdbc.sql("""
                select pl.project_id, ps.db_connection_id, ps.environment_id
                  from pipeline_stage ps join pipeline pl on pl.id = ps.pipeline_id
                 where ps.id = :stage and ps.pipeline_id = :pipeline
                """).param("stage", stage).param("pipeline", pipeline).query().listOfRows()) {
            projects.add((UUID) row.get("project_id"));
            connections.add((UUID) row.get("db_connection_id"));
            environments.add((UUID) row.get("environment_id"));
        }
        return new ScopeTarget(projects, connections, Set.of(pipeline), environments);
    }

    /** A promotion is about the stage it was asked for. */
    public ScopeTarget forPromotion(UUID promotion) {
        List<Map<String, Object>> rows = jdbc.sql("select pipeline_id, stage_id from promotion where id = :id")
                .param("id", promotion).query().listOfRows();
        return rows.isEmpty() ? ScopeTarget.nothing() : forStage((UUID) rows.get(0).get("pipeline_id"), (UUID) rows.get(0).get("stage_id"));
    }

    private UUID connectionOf(String sql, UUID id) {
        Optional<UUID> found = jdbc.sql(sql).param("id", id).query(UUID.class).optional();
        return found.orElse(null);
    }
}
