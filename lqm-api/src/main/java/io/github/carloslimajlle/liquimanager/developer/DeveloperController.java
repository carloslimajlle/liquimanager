package io.github.carloslimajlle.liquimanager.developer;

import java.net.URI;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.Labels;
import io.github.carloslimajlle.liquimanager.common.LqmException;

/**
 * Developer Mode (screen 03, RF-AUT-06): a changelog as it lives in the repository, next to what the platform knows
 * about it. Read-only: running things is the CLI's job, and the screen only shows the command.
 *
 * <p>The files come from GitLab through the backend, with a read token that is configuration
 * ({@code lqm.gitlab.read-token}) and never leaves it. What the platform knows (which changesets are applied in
 * production, the last findings, the last flow) comes from its own tables.
 */
@RestController
@RequestMapping(Api.V1 + "/projects/{projectId}/changelogs/{id}")
class DeveloperController {

    /** A branch, a tag or a commit: nothing that could add a parameter or climb out of the query string. */
    private static final Pattern REF = Pattern.compile("[0-9A-Za-z._/-]{1,100}");

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final RepositoryFiles repository;

    DeveloperController(JdbcClient jdbc, WorkspaceContext workspaces, RepositoryFiles files) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.repository = files;
    }

    // ------------------------------------------------------------------- detail

    @GetMapping
    Map<String, Object> detail(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                               @PathVariable UUID projectId, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> changelog = changelog(workspace, projectId, id);

        List<Map<String, Object>> changesets = jdbc.sql("""
                select cs.cs_id, cs.author, cs.file_path, cs.has_rollback, cs.labels,
                       case when bool_or(s.status = 'FAILED') then 'FAILED'
                            when bool_or(s.status in ('EXECUTED', 'OUT_OF_PROCESS')) then 'EXECUTED'
                            else 'PENDING' end as production
                  from changeset cs
                  left join (select s.changeset_id, s.status
                               from changeset_state s
                               join db_connection c on c.id = s.db_connection_id
                               join environment e on e.id = c.environment_id
                              where e.is_production) s on s.changeset_id = cs.id
                 where cs.changelog_id = :cl
                 group by cs.id, cs.cs_id, cs.author, cs.file_path, cs.has_rollback, cs.labels, cs.first_seen_at
                 order by cs.first_seen_at, cs.cs_id
                """).param("cl", id).query().listOfRows();

        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> files = new LinkedHashSet<>();
        files.add((String) changelog.get("path"));
        int withRollback = 0;
        String release = null;
        for (Map<String, Object> row : changesets) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.get("cs_id"));
            item.put("author", row.get("author"));
            item.put("filePath", row.get("file_path"));
            item.put("hasRollback", row.get("has_rollback"));
            item.put("release", Labels.release((String) row.get("labels")));
            item.put("production", row.get("production"));
            rows.add(item);
            files.add((String) row.get("file_path"));
            if (Boolean.TRUE.equals(row.get("has_rollback"))) {
                withRollback++;
            }
            String candidate = Labels.release((String) row.get("labels"));
            if (candidate != null && (release == null || VERSIONS.compare(candidate, release) > 0)) {
                release = candidate;
            }
        }

        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("changesets", rows.size());
        kpis.put("withRollback", withRollback);
        kpis.put("release", release);
        kpis.put("lastFlow", lastFlow(id));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", changelog.get("id"));
        out.put("name", changelog.get("name"));
        out.put("path", changelog.get("path"));
        out.put("format", changelog.get("format"));
        out.put("project", Map.of("id", changelog.get("project_id"), "slug", changelog.get("slug"),
                "name", changelog.get("project_name")));
        out.put("branch", changelog.get("default_branch"));
        out.put("sourceConfigured", repository.configured() && changelog.get("repo_url") != null);
        out.put("kpis", kpis);
        out.put("changesets", rows);
        out.put("files", new ArrayList<>(files));
        out.put("findings", latestFindings(id));
        return out;
    }

    /** "release-2.10.0" after "release-2.9.0": numbers compare as numbers. */
    private static final Comparator<String> VERSIONS = (a, b) -> {
        String[] left = a.split("[^0-9]+");
        String[] right = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            long l = i < left.length && !left[i].isEmpty() ? Long.parseLong(left[i]) : 0;
            long r = i < right.length && !right[i].isEmpty() ? Long.parseLong(right[i]) : 0;
            if (l != r) {
                return Long.compare(l, r);
            }
        }
        return 0;
    };

    private Map<String, Object> lastFlow(UUID changelogId) {
        return jdbc.sql("""
                select o.id, o.status, o.duration_ms, o.started_at,
                       (select count(*) from operation c where c.parent_operation_id = o.id) as stages
                  from operation o where o.changelog_id = :cl and o.command = 'flow'
                 order by o.started_at desc limit 1
                """).param("cl", changelogId).query().listOfRows().stream().findFirst().map(flow -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("operationId", flow.get("id"));
                    out.put("status", flow.get("status"));
                    out.put("durationMs", flow.get("duration_ms"));
                    out.put("stages", flow.get("stages"));
                    out.put("startedAt", ((Timestamp) flow.get("started_at")).toInstant().toString());
                    return out;
                }).orElse(null);
    }

    /** The findings of the most recent operation on this changelog that evaluated any: what the PROBLEMS tab lists. */
    private List<Map<String, Object>> latestFindings(UUID changelogId) {
        return jdbc.sql("""
                select c.code, f.severity, f.result, f.message, f.line, cs.cs_id, cs.file_path
                  from policy_finding f
                  join policy_check c on c.id = f.check_id
                  left join changeset cs on cs.id = f.changeset_id
                 where f.operation_id = (select o.id from operation o
                                          where o.changelog_id = :cl
                                            and exists (select 1 from policy_finding pf where pf.operation_id = o.id)
                                          order by o.started_at desc limit 1)
                 order by f.severity desc, c.code
                """).param("cl", changelogId).query().listOfRows().stream().map(f -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("check", f.get("code"));
                    item.put("severity", f.get("severity"));
                    item.put("result", f.get("result"));
                    item.put("message", f.get("message"));
                    item.put("line", f.get("line"));
                    item.put("changeset", f.get("cs_id"));
                    item.put("filePath", f.get("file_path"));
                    return item;
                }).toList();
    }

    // -------------------------------------------------------------------- files

    /**
     * One file of the repository, as GitLab has it: at the default branch, or at {@code commit} (the commit of an
     * operation, to see what actually ran).
     */
    @GetMapping(value = "/files", produces = MediaType.TEXT_PLAIN_VALUE)
    String file(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                @PathVariable UUID projectId, @PathVariable UUID id,
                @RequestParam String path, @RequestParam(required = false) String commit) {
        UUID workspace = workspaces.resolve(ws);
        Map<String, Object> changelog = changelog(workspace, projectId, id);
        String repo = (String) changelog.get("repo_url");
        if (repo == null || repo.isBlank()) {
            throw LqmException.invalid("the project has no repository URL");
        }
        if (!repository.configured()) {
            throw LqmException.invalid("GitLab is not configured (lqm.gitlab.base-url and lqm.gitlab.read-token)");
        }
        String ref = commit == null || commit.isBlank() ? (String) changelog.get("default_branch") : commit.trim();
        if (ref == null || !REF.matcher(ref).matches() || ref.contains("..")) {
            throw LqmException.invalid("commit is not a branch, tag or commit id");
        }
        String cleaned = path.replace('\\', '/');
        if (cleaned.isBlank() || cleaned.startsWith("/") || cleaned.contains("..")) {
            throw LqmException.invalid("path must be relative to the repository");
        }

        String project = projectPath(repo);
        for (String candidate : candidates(cleaned, (String) changelog.get("path"))) {
            String text = repository.fetch(project, candidate, ref);
            if (text != null) {
                return text;
            }
        }
        throw LqmException.notFound("file " + cleaned);
    }

    static List<String> candidates(String path, String masterPath) {
        return RepositoryFiles.candidates(path, masterPath);
    }

    static String projectPath(String repoUrl) {
        return RepositoryFiles.projectPath(repoUrl);
    }

    private Map<String, Object> changelog(UUID workspace, UUID projectId, UUID id) {
        return jdbc.sql("""
                select cl.id, cl.name, cl.path, cl.format, p.id as project_id, p.slug, p.name as project_name,
                       p.repo_url, p.default_branch
                  from changelog cl join project p on p.id = cl.project_id
                 where cl.id = :id and p.id = :project and p.workspace_id = :ws
                """).param("id", id).param("project", projectId).param("ws", workspace)
                .query().listOfRows().stream().findFirst().orElseThrow(() -> LqmException.notFound("changelog"));
    }
}
