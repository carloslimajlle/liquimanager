package io.github.carloslimajlle.liquimanager.changesets;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.drift.TargetConnections;
import io.github.carloslimajlle.liquimanager.policy.cdc.CdcCatalog;
import io.github.carloslimajlle.liquimanager.policy.cdc.CdcImpact;
import io.github.carloslimajlle.liquimanager.policy.cdc.CdcRecapture;
import io.github.carloslimajlle.liquimanager.policy.cdc.CdcTable;

/**
 * What a set of changesets would do to the tables that SQL Server Change Data Capture tracks in a database, and the changesets that
 * give those tables their capture instance back.
 *
 * <p>A capture instance takes the columns the table has on the day it is created. A changeset that adds, retypes, drops or renames a
 * column of a tracked table leaves it out of step, and the consumer (Debezium, in the usual setup) is left reading a table that no
 * longer looks like its instance until somebody creates the instance again. This answers BEFORE the deploy: which changesets touch
 * which tracked tables, and the two-step recapture to add to the same merge request.
 *
 * <p>The set is chosen the way {@link ChangesetSqlController} does it, and the SQL it reads is the one that endpoint generates for
 * SQL Server. The state of CDC comes from the database the changesets are going to ({@code connection}), read with the account of its
 * secret: queries only, like the rest of what the server does on a monitored database. SQL Server only.
 */
@RestController
@RequestMapping(Api.V1)
class ChangesetCdcController {

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final ChangesetSqlService sql;
    private final TargetConnections targets;

    ChangesetCdcController(JdbcClient jdbc, WorkspaceContext workspaces, ChangesetSqlService sql, TargetConnections targets) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.sql = sql;
        this.targets = targets;
    }

    @GetMapping("/projects/{projectId}/changesets/cdc")
    Map<String, Object> cdc(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID projectId,
                            @RequestParam UUID connection,
                            @RequestParam(required = false) List<UUID> ids,
                            @RequestParam(required = false) String release,
                            @RequestParam(required = false) String tag,
                            @RequestParam(required = false) String notIn,
                            @RequestParam(required = false) String ref) {
        UUID workspace = workspaces.resolve(ws);
        List<Map<String, Object>> target = jdbc.sql("select slug, engine from db_connection where id = :id and workspace_id = :ws and archived_at is null")
                .param("id", connection).param("ws", workspace).query().listOfRows();
        if (target.isEmpty()) {
            throw LqmException.notFound("connection");
        }
        if (!"SQLSERVER".equals(target.get(0).get("engine"))) {
            throw LqmException.invalid("the CDC check is for SQL Server connections only");
        }

        ChangesetSqlService.Run run = sql.run(workspace, projectId, ids, release, tag, notIn, "mssql", ref);
        CdcCatalog.State state = read(workspace, connection, (String) target.get(0).get("slug"));

        List<Map<String, Object>> items = new ArrayList<>();
        Set<String> toRecapture = new LinkedHashSet<>();
        Set<String> broken = new LinkedHashSet<>();
        int unknown = 0;
        for (Map<String, Object> row : run.rows()) {
            Map<String, Object> result = run.results().get((UUID) row.get("id"));
            @SuppressWarnings("unchecked")
            List<String> statements = (List<String>) result.get("statements");
            List<CdcImpact.Impact> impacts = CdcImpact.of(statements, state.tables());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("changesetId", row.get("cs_id"));
            item.put("author", row.get("author"));
            item.put("filePath", row.get("file_path"));
            item.put("warning", result.get("warning"));
            List<Map<String, Object>> list = new ArrayList<>();
            for (CdcImpact.Impact impact : impacts) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("table", impact.table());
                entry.put("kind", impact.kind().name());
                entry.put("detail", impact.detail());
                entry.put("breaking", impact.kind().breaking());
                entry.put("needsRecapture", impact.kind().needsRecapture());
                list.add(entry);
                if (impact.kind().needsRecapture()) {
                    toRecapture.add(CdcTable.key(impact.table().substring(0, impact.table().indexOf('.')), impact.table().substring(impact.table().indexOf('.') + 1)));
                }
                if (impact.kind().breaking()) {
                    broken.add(impact.table());
                }
            }
            item.put("impacts", list);
            items.add(item);
            if (result.get("warning") != null) {
                unknown++;
            }
        }

        List<CdcTable> affected = state.tables().stream().filter(table -> toRecapture.contains(table.key())).toList();
        List<Map<String, Object>> tables = new ArrayList<>();
        for (CdcTable table : state.tables()) {
            if (toRecapture.contains(table.key()) || broken.contains(table.schema() + "." + table.table())) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("table", table.schema() + "." + table.table());
                entry.put("instances", table.instances().stream().map(CdcTable.Instance::name).toList());
                tables.add(entry);
            }
        }

        List<String> notes = new ArrayList<>();
        boolean renamed = items.stream().anyMatch(i -> kinds(i).contains("COLUMN_RENAMED"));
        boolean dropped = items.stream().anyMatch(i -> kinds(i).contains("TABLE_DROPPED"));
        if (renamed) {
            notes.add("O SQL Server recusa renomear uma coluna de tabela com CDC (erro 4928). Remova a instância antes de renomear (passo 2)"
                    + " e crie de novo depois (passo 1); o consumidor perde a continuidade nessa janela.");
        }
        if (dropped) {
            notes.add("Apagar uma tabela com CDC apaga as instâncias dela. Não há o que recapturar: avise quem consome a tabela.");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", run.mode());
        out.put("selection", run.selection());
        out.put("connection", Map.of("id", connection, "slug", target.get(0).get("slug")));
        out.put("cdcEnabled", state.enabled());
        out.put("trackedTables", state.tables().size());
        out.put("count", run.rows().size());
        out.put("unknown", unknown);
        out.put("breaking", broken.size());
        out.put("changesets", items);
        out.put("tables", tables);
        out.put("notes", notes);
        if (!affected.isEmpty()) {
            CdcRecapture.Plan plan = CdcRecapture.of(affected);
            Map<String, Object> recapture = new LinkedHashMap<>();
            recapture.put("tables", plan.tables());
            recapture.put("createSql", plan.createSql());
            recapture.put("removeSql", plan.removeSql());
            recapture.put("createChangesets", plan.createChangesets());
            recapture.put("removeChangesets", plan.removeChangesets());
            recapture.put("notes", plan.notes());
            out.put("recapture", recapture);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> kinds(Map<String, Object> item) {
        Set<String> kinds = new LinkedHashSet<>();
        for (Map<String, Object> impact : (List<Map<String, Object>>) item.get("impacts")) {
            kinds.add((String) impact.get("kind"));
        }
        return kinds;
    }

    private CdcCatalog.State read(UUID workspace, UUID connection, String slug) {
        try (TargetConnections.Target target = targets.open(workspace, connection)) {
            return CdcCatalog.read(target.jdbc());
        } catch (SQLException e) {
            // The driver's message can carry the host and the account; say only that it failed and why in general terms.
            throw new LqmException(HttpStatus.BAD_GATEWAY, "LQM-050",
                    "could not read the CDC state of " + slug + " (SQLState " + e.getSQLState() + "): the account needs to see cdc.change_tables");
        }
    }
}
