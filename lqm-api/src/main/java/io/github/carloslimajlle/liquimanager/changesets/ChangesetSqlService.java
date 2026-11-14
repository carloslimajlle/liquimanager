package io.github.carloslimajlle.liquimanager.changesets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.changesets.ChangesetSqlGenerator.Generated;
import io.github.carloslimajlle.liquimanager.changesets.ChangesetSqlGenerator.Target;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.developer.RepositoryFiles;

/**
 * Picks a set of changesets of a project (the changesets themselves, a release, a tag, or what has not reached an environment) and
 * generates their SQL from the repository, without running anything. The one place that does it, for everything that needs the SQL
 * of changesets: the script people take to a DBA and the check of what a change does to the tables CDC tracks.
 *
 * <p>Every changeset is in the answer, in the order the project saw them. One whose SQL could not be generated carries a
 * {@code warning} instead of statements, so one bad file does not hide the rest.
 */
@Component
public class ChangesetSqlService {

    static final int MAX_CHANGESETS = 300;

    private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{7,40}");
    private static final Pattern REF = Pattern.compile("[0-9A-Za-z._/-]{1,100}");
    private static final Pattern NAME = Pattern.compile("[0-9A-Za-z._-]{1,100}");

    /**
     * @param mode    CHANGESETS, RELEASE, TAG or NOT_PROMOTED
     * @param rows    the changesets: id, cs_id, author, file_path, ...
     * @param results per changeset id: {@code statements} (the SQL), {@code text} (the SQL as a script), {@code count}, {@code ref} and {@code warning}
     */
    public record Run(String mode, String selection, String dialect, List<Map<String, Object>> rows, Map<UUID, Map<String, Object>> results) {
    }

    private final JdbcClient jdbc;
    private final RepositoryFiles files;
    private final ChangesetSqlGenerator generator;

    ChangesetSqlService(JdbcClient jdbc, RepositoryFiles files, ChangesetSqlGenerator generator) {
        this.jdbc = jdbc;
        this.files = files;
        this.generator = generator;
    }

    /**
     * @param dialect the dialect asked for, or null to take the one of where the changesets live
     * @throws LqmException 404 for a project, changeset or environment that is not there; 422 for a selection that is not valid
     */
    public Run run(UUID workspace, UUID projectId, List<UUID> ids, String release, String tag, String notIn, String dialect, String ref) {
        if (jdbc.sql("select count(*) from project where id = :id and workspace_id = :ws")
                .param("id", projectId).param("ws", workspace).query(Long.class).single() == 0) {
            throw LqmException.notFound("project");
        }
        int selectors = (ids == null || ids.isEmpty() ? 0 : 1) + (blank(release) == null ? 0 : 1) + (blank(tag) == null ? 0 : 1)
                + (blank(notIn) == null ? 0 : 1);
        if (selectors != 1) {
            throw LqmException.invalid("choose exactly one of ids, release, tag or notIn");
        }
        if (blank(ref) != null && (!REF.matcher(ref.trim()).matches() || ref.contains(".."))) {
            throw LqmException.invalid("ref is not a branch, tag or commit id");
        }
        String chosenDialect = blank(dialect) == null ? null : blank(dialect).toLowerCase();
        if (chosenDialect != null && !ChangesetSqlGenerator.DIALECTS.contains(chosenDialect)) {
            throw LqmException.invalid("dialect must be one of " + String.join(", ", new java.util.TreeSet<>(ChangesetSqlGenerator.DIALECTS)));
        }

        Map<String, Object> params = new HashMap<>();
        params.put("project", projectId);
        params.put("ws", workspace);
        String mode;
        String selection;
        String filter;
        UUID environmentId = null;
        if (ids != null && !ids.isEmpty()) {
            mode = "CHANGESETS";
            selection = ids.size() + " changeset(s)";
            filter = " and cs.id in (:ids)";
            params.put("ids", ids);
        } else if (blank(release) != null) {
            requireName(release, "release");
            mode = "RELEASE";
            selection = release.trim();
            filter = " and (',' || replace(coalesce(cs.labels, ''), ' ', '') || ',') like :release";
            params.put("release", "%," + release.trim() + ",%");
        } else if (blank(tag) != null) {
            requireName(tag, "tag");
            mode = "TAG";
            selection = tag.trim();
            filter = """
                     and (exists (select 1 from operation_changeset oc join operation o on o.id = oc.operation_id and o.started_at = oc.operation_started_at
                                   where oc.changeset_id = cs.id and o.tag = :tag)
                          or exists (select 1 from changeset_state s where s.changeset_id = cs.id and s.tag = :tag))
                    """;
            params.put("tag", tag.trim());
        } else {
            mode = "NOT_PROMOTED";
            String code = notIn.trim().toUpperCase();
            List<Map<String, Object>> env = jdbc.sql("select id, sort_order from environment where workspace_id = :ws and code = :code")
                    .param("ws", workspace).param("code", code).query().listOfRows();
            if (env.isEmpty()) {
                throw LqmException.notFound("environment");
            }
            environmentId = (UUID) env.get(0).get("id");
            selection = code;
            filter = """
                     and exists (select 1 from changeset_state s join db_connection c on c.id = s.db_connection_id
                                   join environment e on e.id = c.environment_id
                                  where s.changeset_id = cs.id and s.status = 'EXECUTED' and e.workspace_id = :ws and e.sort_order < :order)
                     and not exists (select 1 from changeset_state s join db_connection c on c.id = s.db_connection_id
                                      where s.changeset_id = cs.id and s.status = 'EXECUTED' and c.environment_id = :env)
                    """;
            params.put("order", ((Number) env.get(0).get("sort_order")).intValue());
            params.put("env", environmentId);
        }

        List<Map<String, Object>> rows = jdbc.sql("""
                select cs.id, cs.cs_id, cs.author, cs.file_path, cl.path as changelog_path, p.repo_url, p.default_branch
                  from changeset cs join changelog cl on cl.id = cs.changelog_id join project p on p.id = cl.project_id
                 where cl.project_id = :project and p.workspace_id = :ws
                """ + filter + " order by cs.first_seen_at, cs.file_path, cs.cs_id limit " + (MAX_CHANGESETS + 1))
                .params(params).query().listOfRows();
        if (rows.size() > MAX_CHANGESETS) {
            throw LqmException.invalid("more than " + MAX_CHANGESETS + " changesets: narrow the selection");
        }
        if (mode.equals("CHANGESETS") && rows.size() != new java.util.HashSet<>(ids).size()) {
            throw LqmException.notFound("changeset");
        }

        List<UUID> rowIds = rows.stream().map(r -> (UUID) r.get("id")).toList();
        String usedDialect = chosenDialect != null ? chosenDialect : defaultDialect(environmentId, rowIds);

        // One file is read and generated once, however many of the selected changesets live in it.
        Map<String, List<Map<String, Object>>> byFile = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byFile.computeIfAbsent(row.get("repo_url") + "|" + row.get("changelog_path") + "|" + row.get("file_path"), k -> new ArrayList<>()).add(row);
        }
        Map<UUID, Map<String, Object>> results = new HashMap<>();
        for (List<Map<String, Object>> group : byFile.values()) {
            results.putAll(generateGroup(group, usedDialect, blank(ref)));
        }

        return new Run(mode, selection, usedDialect, rows, results);
    }

    /** The SQL of every changeset of one file; each entry has {@code text}, {@code count}, {@code ref} and, when it failed, {@code warning}. */
    private Map<UUID, Map<String, Object>> generateGroup(List<Map<String, Object>> group, String dialect, String ref) {
        Map<String, Object> first = group.get(0);
        Map<UUID, Map<String, Object>> out = new HashMap<>();
        String repo = (String) first.get("repo_url");
        if (repo == null || repo.isBlank()) {
            return failAll(group, "NO_REPOSITORY");
        }
        if (!files.configured()) {
            return failAll(group, "NOT_CONFIGURED");
        }
        String project = RepositoryFiles.projectPath(repo);
        List<String> refs = new ArrayList<>();
        if (ref != null) {
            refs.add(ref);
        } else {
            if (first.get("default_branch") != null) {
                refs.add((String) first.get("default_branch"));
            }
            for (String commit : jdbc.sql("""
                    select o.git_commit
                      from operation_changeset oc join operation o on o.id = oc.operation_id and o.started_at = oc.operation_started_at
                     where oc.changeset_id in (:ids) and o.git_commit is not null
                     group by o.git_commit order by max(o.started_at) desc limit 3
                    """).param("ids", group.stream().map(r -> r.get("id")).toList()).query(String.class).list()) {
                if (!refs.contains(commit) && COMMIT.matcher(commit).matches()) {
                    refs.add(commit);
                }
            }
        }

        boolean unreachable = false;
        for (String candidateRef : refs) {
            for (String candidate : RepositoryFiles.candidates((String) first.get("file_path"), (String) first.get("changelog_path"))) {
                String text;
                try {
                    text = files.fetch(project, candidate, candidateRef);
                } catch (LqmException e) {
                    unreachable = true;
                    break;
                }
                if (text == null) {
                    continue;
                }
                List<Target> targets = group.stream().map(r -> new Target((String) r.get("cs_id"), (String) r.get("author"))).toList();
                Map<Target, Generated> generated = generator.generate(candidate, text, dialect, targets);
                for (Map<String, Object> row : group) {
                    Generated g = generated.get(new Target((String) row.get("cs_id"), (String) row.get("author")));
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("ref", candidateRef);
                    entry.put("count", g.statements().size());
                    entry.put("warning", g.warning());
                    entry.put("statements", g.statements());
                    entry.put("text", format(g.statements()));
                    out.put((UUID) row.get("id"), entry);
                }
                return out;
            }
        }
        return failAll(group, unreachable ? "UNREACHABLE" : "NOT_FOUND");
    }

    private static Map<UUID, Map<String, Object>> failAll(List<Map<String, Object>> group, String warning) {
        Map<UUID, Map<String, Object>> out = new HashMap<>();
        for (Map<String, Object> row : group) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("count", 0);
            entry.put("statements", List.of());
            entry.put("warning", warning);
            out.put((UUID) row.get("id"), entry);
        }
        return out;
    }

    private static String format(List<String> statements) {
        StringBuilder text = new StringBuilder();
        for (String statement : statements) {
            String trimmed = statement.trim();
            text.append(trimmed).append(trimmed.endsWith(";") ? "" : ";").append('\n');
        }
        return text.toString();
    }

    /** Where the changesets live decides the dialect: the engine of the target environment, else the most common one they ran on. */
    private String defaultDialect(UUID environmentId, List<UUID> changesetIds) {
        if (environmentId != null) {
            for (String engine : jdbc.sql("select engine from db_connection where environment_id = :env order by slug limit 1")
                    .param("env", environmentId).query(String.class).list()) {
                if (ChangesetSqlGenerator.dialectOf(engine) != null) {
                    return ChangesetSqlGenerator.dialectOf(engine);
                }
            }
        }
        if (!changesetIds.isEmpty()) {
            for (String engine : jdbc.sql("""
                    select c.engine from changeset_state s join db_connection c on c.id = s.db_connection_id
                     where s.changeset_id in (:ids) group by c.engine order by count(*) desc limit 1
                    """).param("ids", changesetIds).query(String.class).list()) {
                if (ChangesetSqlGenerator.dialectOf(engine) != null) {
                    return ChangesetSqlGenerator.dialectOf(engine);
                }
            }
        }
        return "mssql";
    }

    private static void requireName(String value, String what) {
        if (!NAME.matcher(value.trim()).matches()) {
            throw LqmException.invalid(what + " has characters that cannot be in it");
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
