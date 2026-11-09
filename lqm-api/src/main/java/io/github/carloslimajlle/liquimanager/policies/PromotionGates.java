package io.github.carloslimajlle.liquimanager.policies;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * "What is in a later environment has to be in the one before it." The order is the pipeline's: a stage is held back by the stage
 * with the next lower {@code sort_order}. What the earlier stage has is what LiquiManager was told about its database, by the deploy
 * report and by the scanner ({@code changeset_state}); so a DEV that was never reported to LiquiManager holds everything back, on
 * purpose.
 */
@Component
class PromotionGates {

    private final JdbcClient jdbc;

    PromotionGates(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Map<String, Object> forDeploy(UUID workspace, String projectSlug, String env) {
        Map<String, Object> out = new LinkedHashMap<>();
        // The earliest stage-before of this environment among the project's pipelines. The first stage has none: nothing holds it.
        List<Map<String, Object>> previous = jdbc.sql("""
                select prev.db_connection_id, pe.code as previous_env
                  from project p
                  join pipeline pl on pl.project_id = p.id
                  join pipeline_stage st on st.pipeline_id = pl.id
                  join environment e on e.id = st.environment_id and upper(e.code) = upper(:env)
                  join pipeline_stage prev on prev.pipeline_id = pl.id
                       and prev.sort_order = (select max(x.sort_order) from pipeline_stage x
                                               where x.pipeline_id = pl.id and x.sort_order < st.sort_order)
                  join environment pe on pe.id = prev.environment_id
                 where p.workspace_id = :ws and p.slug = :slug
                 order by pl.id limit 1
                """).param("ws", workspace).param("slug", projectSlug).param("env", env).query().listOfRows();
        if (previous.isEmpty()) {
            out.put("gated", false);
            return out;
        }
        UUID connection = (UUID) previous.get(0).get("db_connection_id");
        List<Map<String, Object>> executed = new ArrayList<>();
        jdbc.sql("""
                select cs.cs_id, cs.author, cs.file_path, s.applied_checksum
                  from changeset_state s join changeset cs on cs.id = s.changeset_id
                 where s.db_connection_id = :conn and s.status = 'EXECUTED'
                """).param("conn", connection).query((rs, n) -> {
                    Map<String, Object> key = new LinkedHashMap<>();
                    key.put("id", rs.getString("cs_id"));
                    key.put("author", rs.getString("author"));
                    key.put("filePath", rs.getString("file_path"));
                    // What that database ran, when known: the deploy also refuses a changeset that is there in another version.
                    key.put("checksum", rs.getString("applied_checksum"));
                    return key;
                }).list().forEach(executed::add);
        out.put("gated", true);
        out.put("previousEnv", previous.get(0).get("previous_env"));
        out.put("executed", executed);
        return out;
    }
}
