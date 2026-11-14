package io.github.carloslimajlle.liquimanager.changesets;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;

/**
 * The SQL of a set of changesets, generated from the repository WITHOUT running anything, so a person can hand it to whoever applies
 * it by hand (a DBA, a window, a database the collectors do not reach).
 *
 * <p>The set is chosen one way: the changesets themselves ({@code ids}), a {@code release} (the {@code release-*} label), a Liquibase
 * {@code tag} (what the operations carrying it ran), or {@code notIn}, an environment: what ran in an earlier environment and has not
 * reached that one yet. The dialect is the one asked for, else the engine of where the changesets live. The selection and the
 * generation are {@link ChangesetSqlService}'s.
 *
 * <p>Read only. The answer is always 200 once the set is valid: a changeset whose file cannot be read or generated carries a
 * {@code warning}, so one bad file does not hide the rest of the script.
 */
@RestController
@RequestMapping(Api.V1)
class ChangesetSqlController {

    private final WorkspaceContext workspaces;
    private final ChangesetSqlService sql;

    ChangesetSqlController(WorkspaceContext workspaces, ChangesetSqlService sql) {
        this.workspaces = workspaces;
        this.sql = sql;
    }

    @GetMapping("/projects/{projectId}/changesets/sql")
    Map<String, Object> sql(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID projectId,
                            @RequestParam(required = false) List<UUID> ids,
                            @RequestParam(required = false) String release,
                            @RequestParam(required = false) String tag,
                            @RequestParam(required = false) String notIn,
                            @RequestParam(required = false) String dialect,
                            @RequestParam(required = false) String ref) {
        ChangesetSqlService.Run run = sql.run(workspaces.resolve(ws), projectId, ids, release, tag, notIn, dialect, ref);

        StringBuilder script = new StringBuilder();
        script.append("-- LiquiManager: SQL generated from the repository; nothing was executed.\n")
                .append("-- Selection: ").append(run.mode()).append(' ').append(run.selection()).append(" | dialect: ").append(run.dialect())
                .append(" | ").append(Instant.now()).append("\n");
        List<Map<String, Object>> items = new ArrayList<>();
        int generated = 0;
        for (Map<String, Object> row : run.rows()) {
            Map<String, Object> result = run.results().get((UUID) row.get("id"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("id"));
            item.put("changesetId", row.get("cs_id"));
            item.put("author", row.get("author"));
            item.put("filePath", row.get("file_path"));
            item.put("ref", result.get("ref"));
            item.put("statements", result.get("count"));
            item.put("warning", result.get("warning"));
            items.add(item);
            script.append('\n').append("-- Changeset ").append(row.get("file_path")).append("::").append(row.get("cs_id")).append("::")
                    .append(row.get("author"));
            if (result.get("warning") != null) {
                script.append("\n-- SQL not generated: ").append(result.get("warning")).append('\n');
                continue;
            }
            generated++;
            script.append(" (").append(result.get("ref")).append(")\n").append(result.get("text"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", run.mode());
        out.put("selection", run.selection());
        out.put("dialect", run.dialect());
        out.put("count", run.rows().size());
        out.put("generated", generated);
        out.put("changesets", items);
        out.put("sql", script.toString());
        return out;
    }

}
