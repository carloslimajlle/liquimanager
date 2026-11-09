package io.github.carloslimajlle.liquimanager.changesets;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.developer.RepositoryFiles;

/**
 * A changeset that already ran and was edited afterwards ({@code ChecksumChanged}). The database holds the checksum of what it ran;
 * when the file now hashes differently, Liquibase refuses the whole deploy. This finds it earlier, in the merge request, and says what
 * changed.
 *
 * <p>What is compared is the checksum the control plane recorded when the changeset was executed in an environment
 * ({@code changeset_state.applied_checksum}, fed by the executions and by the scanner) with the one the caller computes from the file
 * it is about to run. The lines that differ come from the repository, between the commit that applied it and the current one.
 *
 * <p>Whatever cannot be known is left out, never guessed: no applied checksum recorded, a repository that cannot be read. A false
 * "this was edited" blocks a merge request.
 */
@Component
public class ChecksumChecks {

    /** A changeset as the caller sees it now. */
    public record Item(String id, String author, String filePath, String checksum) {
    }

    private record Applied(UUID changesetId, String id, String author, String filePath, String checksum, String commit,
                           java.time.Instant at, String connection, String environment, String changelogPath) {
    }

    private final JdbcClient jdbc;
    private final RepositoryFiles files;

    ChecksumChecks(JdbcClient jdbc, RepositoryFiles files) {
        this.jdbc = jdbc;
        this.files = files;
    }

    // ------------------------------------------------------------ from a run

    /**
     * Among {@code items}, the ones executed in {@code env} with another checksum than the one now, and the ones whose author or file
     * changed after they were executed (which Liquibase takes for a new changeset). {@code commit} is where the new
     * version is read from (the head of the merge request); without it, the default branch.
     */
    public List<Map<String, Object>> changed(UUID workspace, String projectSlug, String env, String commit, String changelog,
                                             List<Item> items) {
        List<Map<String, Object>> project = jdbc.sql("select id, repo_url, default_branch from project where workspace_id = :ws and slug = :slug")
                .param("ws", workspace).param("slug", projectSlug).query().listOfRows();
        if (project.isEmpty() || items == null || items.isEmpty()) {
            return List.of();
        }
        UUID projectId = (UUID) project.get(0).get("id");
        String repo = (String) project.get(0).get("repo_url");
        String newRef = commit != null && !commit.isBlank() ? commit.trim() : (String) project.get(0).get("default_branch");

        List<Applied> all = appliedIn(projectId, env);
        Map<String, Applied> applied = new HashMap<>();
        for (Applied a : all) {
            applied.putIfAbsent(key(a.id(), a.author(), a.filePath()), a);
        }
        java.util.Set<String> declared = new java.util.HashSet<>();
        for (Item item : items) {
            declared.add(key(item.id(), item.author(), item.filePath()));
        }

        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> accountedFor = new java.util.HashSet<>();
        for (Item item : items) {
            Applied was = applied.get(key(item.id(), item.author(), item.filePath()));
            if (was == null) {
                Optional<Map<String, Object>> moved = renamed(item, all, declared);
                moved.ifPresent(out::add);
                moved.ifPresent(entry -> accountedFor.add(key((String) entry.get("id"), (String) entry.get("appliedAuthor"),
                        (String) entry.get("appliedFilePath"))));
                continue;
            }
            if (item.checksum() == null || was.checksum().equals(item.checksum())) {
                continue;
            }
            Map<String, Object> entry = describe(was, item.checksum(), repo, newRef);
            out.add(entry);
        }
        out.addAll(removed(all, declared, accountedFor, changelog));
        return out;
    }

    /**
     * What the environment ran and the changelog no longer declares. Liquibase ignores it, so nothing breaks, but the history of the
     * database stops matching the repository: a rollback or a drift can no longer be explained by it. It can be on purpose (a squash, a
     * baseline), so it is a warning, and only said when the caller named the changelog it read: the applied changesets of the project's
     * OTHER changelogs would otherwise all look removed.
     */
    private List<Map<String, Object>> removed(List<Applied> all, java.util.Set<String> declared, java.util.Set<String> accountedFor,
                                              String changelog) {
        if (changelog == null || changelog.isBlank() || declared.isEmpty()) {
            return List.of();
        }
        String wanted = normalise(changelog);
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Applied a : all) {
            String key = key(a.id(), a.author(), a.filePath());
            if (a.changelogPath() == null || !wanted.equals(normalise(a.changelogPath())) || declared.contains(key)
                    || accountedFor.contains(key) || !seen.add(key)) {
                continue;
            }
            Map<String, Object> entry = base(a, a.checksum());
            entry.put("removed", true);
            entry.put("sourceKnown", false);
            out.add(entry);
        }
        return out;
    }

    private static String normalise(String path) {
        return path.replace('\\', '/').replaceFirst("^classpath:", "").replaceFirst("^/+", "").replaceFirst("^\\./", "");
    }

    /**
     * A changeset that is not applied under this id, author and file, while the same id is applied under another author or file that the
     * changelog no longer declares: Liquibase identifies a changeset by the three, so this is a new one to it, it runs again and
     * usually fails on what already exists. If the old one is still declared, this is a different changeset that happens to share the
     * id, and nothing is reported.
     */
    private Optional<Map<String, Object>> renamed(Item item, List<Applied> all, java.util.Set<String> declared) {
        if (item.id() == null) {
            return Optional.empty();
        }
        for (Applied a : all) {
            if (item.id().equals(a.id()) && !declared.contains(key(a.id(), a.author(), a.filePath()))) {
                Map<String, Object> entry = base(a, item.checksum() == null ? a.checksum() : item.checksum());
                entry.put("author", item.author());
                entry.put("filePath", item.filePath());
                entry.put("identityChanged", true);
                entry.put("appliedAuthor", a.author());
                entry.put("appliedFilePath", a.filePath());
                entry.put("sourceKnown", false);
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /** The identity Liquibase gives a changeset: id, author and file ({@code classpath:} and leading slashes are not part of it). */
    private static String key(String id, String author, String filePath) {
        String path = filePath == null ? "" : filePath.replace('\\', '/').replaceFirst("^classpath:", "").replaceFirst("^/+", "");
        return id + "::" + author + "::" + path;
    }

    // -------------------------------------------------------------- of one

    /**
     * Each database where the changeset is executed, with what it ran against what the repository has now ({@code ref}, normally the
     * default branch). {@code changed} is false when they are the same version.
     */
    public List<Map<String, Object>> versions(UUID workspace, UUID changesetId) {
        List<Map<String, Object>> found = jdbc.sql("""
                select cs.checksum, p.id as project_id, p.repo_url, p.default_branch
                  from changeset cs join changelog cl on cl.id = cs.changelog_id join project p on p.id = cl.project_id
                 where cs.id = :id and p.workspace_id = :ws
                """).param("id", changesetId).param("ws", workspace).query().listOfRows();
        if (found.isEmpty()) {
            throw LqmException.notFound("changeset");
        }
        String current = (String) found.get(0).get("checksum");
        String repo = (String) found.get(0).get("repo_url");
        String ref = (String) found.get(0).get("default_branch");

        List<Map<String, Object>> out = new ArrayList<>();
        for (Applied a : appliedOf(changesetId)) {
            Map<String, Object> entry = current == null || a.checksum().equals(current)
                    ? base(a, current == null ? a.checksum() : current) : describe(a, current, repo, ref);
            entry.put("changed", current != null && !a.checksum().equals(current));
            out.add(entry);
        }
        return out;
    }

    // ---------------------------------------------------------------- shared

    private Map<String, Object> describe(Applied a, String currentChecksum, String repo, String newRef) {
        Map<String, Object> entry = base(a, currentChecksum);
        Optional<List<BlockDiff.Line>> lines = linesChanged(a, repo, newRef);
        entry.put("sourceKnown", lines.isPresent());
        if (lines.isPresent()) {
            entry.put("diff", lines.get().stream().map(l -> Map.of("op", l.op(), "text", l.text())).toList());
            // The checksum differs but no line that counts does: something the text does not show (a Liquibase upgrade, normalised SQL).
            entry.put("equivalent", lines.get().isEmpty());
        }
        return entry;
    }

    private static Map<String, Object> base(Applied a, String currentChecksum) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", a.id());
        entry.put("author", a.author());
        entry.put("filePath", a.filePath());
        entry.put("changesetUuid", a.changesetId());
        entry.put("appliedChecksum", a.checksum());
        entry.put("currentChecksum", currentChecksum);
        entry.put("appliedCommit", a.commit());
        entry.put("appliedAt", a.at());
        entry.put("connection", a.connection());
        entry.put("environment", a.environment());
        return entry;
    }

    /** The differing lines of the changeset's block, between the commit that applied it and {@code newRef}; empty when it cannot be read. */
    private Optional<List<BlockDiff.Line>> linesChanged(Applied a, String repo, String newRef) {
        if (repo == null || repo.isBlank() || !files.configured() || a.commit() == null || newRef == null) {
            return Optional.empty();
        }
        try {
            String project = RepositoryFiles.projectPath(repo);
            Optional<String> before = block(project, a, a.commit());
            Optional<String> after = block(project, a, newRef);
            if (before.isEmpty() || after.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(BlockDiff.diff(before.get(), after.get()));
        } catch (LqmException e) {
            return Optional.empty();
        }
    }

    /** The text of the changeset's own lines in the file at {@code ref}, or the whole file when its block cannot be isolated. */
    private Optional<String> block(String project, Applied a, String ref) {
        for (String candidate : RepositoryFiles.candidates(a.filePath(), a.changelogPath())) {
            String text = files.fetch(project, candidate, ref);
            if (text == null) {
                continue;
            }
            Optional<ChangesetBlocks.Range> range = ChangesetBlocks.find(text, candidate, a.id(), a.author());
            if (range.isEmpty()) {
                return Optional.of(text);
            }
            String[] lines = text.split("\n", -1);
            return Optional.of(String.join("\n", java.util.Arrays.copyOfRange(lines, range.get().start() - 1, range.get().end())));
        }
        return Optional.empty();
    }

    private List<Applied> appliedIn(UUID projectId, String env) {
        return query("""
                where cl.project_id = :project and upper(e.code) = upper(:env)
                """, Map.of("project", projectId, "env", env));
    }

    private List<Applied> appliedOf(UUID changesetId) {
        return query("where cs.id = :cs", Map.of("cs", changesetId));
    }

    private List<Applied> query(String where, Map<String, Object> params) {
        return jdbc.sql("""
                select cs.id as changeset_id, cs.cs_id, cs.author, cs.file_path, cl.path as changelog_path,
                       s.applied_checksum, s.applied_commit, s.deployed_at, c.slug as connection, e.code as environment
                  from changeset_state s
                  join changeset cs on cs.id = s.changeset_id
                  join changelog cl on cl.id = cs.changelog_id
                  join db_connection c on c.id = s.db_connection_id
                  join environment e on e.id = c.environment_id
                """ + where + """
                   and s.status = 'EXECUTED' and s.applied_checksum is not null
                 order by s.deployed_at desc nulls last
                """).params(params).query().listOfRows().stream().map(r -> new Applied(
                (UUID) r.get("changeset_id"), (String) r.get("cs_id"), (String) r.get("author"), (String) r.get("file_path"),
                (String) r.get("applied_checksum"), (String) r.get("applied_commit"),
                r.get("deployed_at") == null ? null : ((Timestamp) r.get("deployed_at")).toInstant(),
                (String) r.get("connection"), (String) r.get("environment"), (String) r.get("changelog_path"))).toList();
    }
}
