package io.github.carloslimajlle.liquimanager.changesets;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

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
import io.github.carloslimajlle.liquimanager.developer.RepositoryFiles;

/**
 * The changeset as the thing to look at: the ones a project has with where each stands, and one changeset with everything the control
 * plane knows about it (its state per database, the operations that touched it, what the policies said, the rollbacks asked for).
 *
 * <p>Read only. A changeset is identified by Liquibase as (id, author, file) inside a changelog; here by the row it has in
 * {@code changeset}.
 */
@RestController
@RequestMapping(Api.V1)
class ChangesetController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final List<String> STATUS_ORDER = List.of("FAILED", "EXECUTED", "ROLLED_BACK", "PENDING");

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final RepositoryFiles files;
    private final ChecksumChecks checksums;

    ChangesetController(JdbcClient jdbc, WorkspaceContext workspaces, RepositoryFiles files, ChecksumChecks checksums) {
        this.checksums = checksums;
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.files = files;
    }

    // ------------------------------------------------------------ of a project

    /**
     * The changesets of a project, newest first, each with its status per environment: the worst one among the environment's databases
     * (failed, then executed, then the rest), so a row reads "executed in DEV, failed in PRD" at a glance.
     */
    @GetMapping("/projects/{projectId}/changesets")
    Map<String, Object> ofProject(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                  @PathVariable UUID projectId,
                                  @RequestParam(required = false) String q,
                                  @RequestParam(required = false) String author,
                                  @RequestParam(required = false) String release,
                                  @RequestParam(required = false) String status,
                                  @RequestParam(required = false) UUID changelog,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "25") int size) {
        UUID workspace = workspaces.resolve(ws);
        requireProject(workspace, projectId);
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageIndex = Math.max(0, page);

        String from = """
                  from changeset cs join changelog cl on cl.id = cs.changelog_id
                 where cl.project_id = :project
                   and (cs.seen_on_default_at is not null or exists (select 1 from changeset_state held where held.changeset_id = cs.id and held.status = 'EXECUTED'))
                   and (cast(:changelog as uuid) is null or cl.id = :changelog)
                   and (cast(:author as text) is null or lower(cs.author) = lower(:author))
                   and (cast(:release as text) is null or cs.labels ilike :releaseLike)
                   and (cast(:q as text) is null or cs.cs_id ilike :qLike or cs.author ilike :qLike or cs.file_path ilike :qLike)
                   and (cast(:status as text) is null or exists (
                          select 1 from changeset_state s where s.changeset_id = cs.id and s.status = :status))
                """;
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("project", projectId);
        params.put("changelog", changelog);
        params.put("author", blank(author));
        params.put("release", blank(release));
        params.put("releaseLike", blank(release) == null ? null : "%" + release.trim() + "%");
        params.put("q", blank(q));
        params.put("qLike", blank(q) == null ? null : "%" + q.trim() + "%");
        params.put("status", blank(status) == null ? null : status.trim().toUpperCase());

        long total = jdbc.sql("select count(*) " + from).params(params).query(Long.class).single();
        List<Map<String, Object>> rows = jdbc.sql("""
                select cs.id, cs.cs_id, cs.author, cs.file_path, cs.labels, cs.has_rollback, cs.is_break_fix, cs.first_seen_at,
                       cs.removed_at, cs.seen_on_default_at is null as only_in_branch,
                       cs.origin_branch, cs.origin_mr_iid, cs.origin_mr_title, cs.origin_mr_url,
                       cl.id as changelog_id, cl.name as changelog
                """ + from + " order by cs.first_seen_at desc, cs.cs_id limit :limit offset :offset")
                .params(params).param("limit", pageSize).param("offset", (long) pageIndex * pageSize)
                .query().listOfRows();

        Map<UUID, Map<String, Map<String, Object>>> states = statesOf(rows.stream().map(r -> (UUID) r.get("id")).toList());
        List<Map<String, Object>> content = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", r.get("id"));
            item.put("changesetId", r.get("cs_id"));
            item.put("author", r.get("author"));
            item.put("filePath", r.get("file_path"));
            item.put("changelogId", r.get("changelog_id"));
            item.put("changelog", r.get("changelog"));
            item.put("release", Labels.release((String) r.get("labels")));
            item.put("hasRollback", r.get("has_rollback"));
            item.put("isBreakFix", r.get("is_break_fix"));
            item.put("firstSeenAt", instant(r.get("first_seen_at")));
            item.put("removedAt", instant(r.get("removed_at")));
            boolean onlyInBranch = Boolean.TRUE.equals(r.get("only_in_branch"));
            item.put("onlyInBranch", onlyInBranch);
            if (onlyInBranch) {
                Map<String, Object> origin = new LinkedHashMap<>();
                origin.put("branch", r.get("origin_branch"));
                origin.put("mrIid", r.get("origin_mr_iid"));
                origin.put("mrTitle", r.get("origin_mr_title"));
                origin.put("mrUrl", r.get("origin_mr_url"));
                item.put("origin", origin);
            }
            item.put("environments", states.getOrDefault((UUID) r.get("id"), Map.of()).values());
            content.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("content", content);
        out.put("page", pageIndex);
        out.put("size", pageSize);
        out.put("totalElements", total);
        out.put("environments", environmentsOf(projectId));
        return out;
    }

    /** What each environment holds of the project: how many changesets are executed, failed or rolled back there, and the last deploy. */
    @GetMapping("/projects/{projectId}/changesets/summary")
    List<Map<String, Object>> summaryOfProject(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws,
                                               @PathVariable UUID projectId) {
        UUID workspace = workspaces.resolve(ws);
        requireProject(workspace, projectId);
        Map<String, Map<String, Object>> byEnv = new LinkedHashMap<>();
        for (String env : environmentsOf(projectId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("environment", env);
            row.put("executed", 0L);
            row.put("failed", 0L);
            row.put("rolledBack", 0L);
            row.put("lastDeployedAt", null);
            byEnv.put(env, row);
        }
        for (Map<String, Object> r : jdbc.sql("""
                select e.code as environment, s.status, count(distinct s.changeset_id) as n, max(s.deployed_at) as last_at
                  from changeset_state s
                  join changeset cs on cs.id = s.changeset_id
                  join changelog cl on cl.id = cs.changelog_id
                  join db_connection c on c.id = s.db_connection_id
                  join environment e on e.id = c.environment_id
                 where cl.project_id = :project
                 group by e.code, s.status
                """).param("project", projectId).query().listOfRows()) {
            Map<String, Object> row = byEnv.computeIfAbsent((String) r.get("environment"), k -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("environment", k);
                created.put("executed", 0L);
                created.put("failed", 0L);
                created.put("rolledBack", 0L);
                created.put("lastDeployedAt", null);
                return created;
            });
            long n = ((Number) r.get("n")).longValue();
            switch ((String) r.get("status")) {
                case "EXECUTED" -> row.put("executed", n);
                case "FAILED" -> row.put("failed", n);
                case "ROLLED_BACK" -> row.put("rolledBack", n);
                default -> { }
            }
            Object at = instant(r.get("last_at"));
            if (at != null && (row.get("lastDeployedAt") == null
                    || ((java.time.Instant) at).isAfter((java.time.Instant) row.get("lastDeployedAt")))) {
                row.put("lastDeployedAt", at);
            }
        }
        return new ArrayList<>(byEnv.values());
    }

    /** The environments the project's changesets can be in, in the order of the pipeline: the columns of the table. */
    private List<String> environmentsOf(UUID projectId) {
        return jdbc.sql("""
                select e.code from environment e
                 where e.id in (select c.environment_id from project_connection pc join db_connection c on c.id = pc.db_connection_id
                                 where pc.project_id = :project)
                    or e.id in (select ps.environment_id from pipeline_stage ps join pipeline pl on pl.id = ps.pipeline_id
                                 where pl.project_id = :project)
                    or e.id in (select c.environment_id from changeset_state s
                                  join changeset cs on cs.id = s.changeset_id join changelog cl on cl.id = cs.changelog_id
                                  join db_connection c on c.id = s.db_connection_id
                                 where cl.project_id = :project)
                 order by e.sort_order, e.code
                """).param("project", projectId).query(String.class).list();
    }

    /** Per changeset, per environment: the worst status among the environment's databases. */
    private Map<UUID, Map<String, Map<String, Object>>> statesOf(List<UUID> ids) {
        Map<UUID, Map<String, Map<String, Object>>> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        for (Map<String, Object> r : jdbc.sql("""
                select s.changeset_id, e.code as environment, s.status, s.deployed_at
                  from changeset_state s
                  join db_connection c on c.id = s.db_connection_id
                  join environment e on e.id = c.environment_id
                 where s.changeset_id in (:ids)
                 order by e.sort_order
                """).param("ids", ids).query().listOfRows()) {
            Map<String, Map<String, Object>> byEnv = out.computeIfAbsent((UUID) r.get("changeset_id"), k -> new TreeMap<>());
            Map<String, Object> current = byEnv.get((String) r.get("environment"));
            String status = (String) r.get("status");
            if (current == null || rank(status) < rank((String) current.get("status"))) {
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("environment", r.get("environment"));
                cell.put("status", status);
                cell.put("deployedAt", instant(r.get("deployed_at")));
                byEnv.put((String) r.get("environment"), cell);
            }
        }
        return out;
    }

    private static int rank(String status) {
        int index = STATUS_ORDER.indexOf(status);
        return index < 0 ? STATUS_ORDER.size() : index;
    }

    // ------------------------------------------------------------------ one

    @GetMapping("/changesets/{id}")
    Map<String, Object> get(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID id) {
        UUID workspace = workspaces.resolve(ws);
        List<Map<String, Object>> found = jdbc.sql("""
                select cs.id, cs.cs_id, cs.author, cs.file_path, cs.checksum, cs.labels, cs.contexts, cs.comments, cs.has_rollback,
                       cs.is_break_fix, cs.first_seen_at, cl.id as changelog_id, cl.name as changelog, p.id as project_id, p.slug as project
                  from changeset cs join changelog cl on cl.id = cs.changelog_id join project p on p.id = cl.project_id
                 where cs.id = :id and p.workspace_id = :ws
                """).param("id", id).param("ws", workspace).query().listOfRows();
        if (found.isEmpty()) {
            throw LqmException.notFound("changeset");
        }
        Map<String, Object> r = found.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.get("id"));
        out.put("changesetId", r.get("cs_id"));
        out.put("author", r.get("author"));
        out.put("filePath", r.get("file_path"));
        out.put("checksum", r.get("checksum"));
        out.put("labels", r.get("labels"));
        out.put("contexts", r.get("contexts"));
        out.put("comments", r.get("comments"));
        out.put("release", Labels.release((String) r.get("labels")));
        out.put("hasRollback", r.get("has_rollback"));
        out.put("isBreakFix", r.get("is_break_fix"));
        out.put("firstSeenAt", instant(r.get("first_seen_at")));
        out.put("changelogId", r.get("changelog_id"));
        out.put("changelog", r.get("changelog"));
        out.put("projectId", r.get("project_id"));
        out.put("project", r.get("project"));

        out.put("states", jdbc.sql("""
                select e.code as environment, c.slug as connection, s.status, s.exec_type, s.deployed_at, s.tag, s.last_operation_id
                  from changeset_state s
                  join db_connection c on c.id = s.db_connection_id
                  join environment e on e.id = c.environment_id
                 where s.changeset_id = :id
                 order by e.sort_order, c.slug
                """).param("id", id).query().listOfRows().stream().map(s -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("environment", s.get("environment"));
            item.put("connection", s.get("connection"));
            item.put("status", s.get("status"));
            item.put("execType", s.get("exec_type"));
            item.put("deployedAt", instant(s.get("deployed_at")));
            item.put("tag", s.get("tag"));
            item.put("operationId", s.get("last_operation_id"));
            return item;
        }).toList());

        out.put("operations", jdbc.sql("""
                select o.id, o.command, o.status, o.started_at, o.executed_by, e.code as environment, c.slug as connection,
                       oc.outcome, oc.error_message
                  from operation_changeset oc
                  join operation o on o.id = oc.operation_id and o.started_at = oc.operation_started_at
                  join db_connection c on c.id = o.db_connection_id
                  join environment e on e.id = c.environment_id
                 where oc.changeset_id = :id
                 order by o.started_at desc limit 50
                """).param("id", id).query().listOfRows().stream().map(o -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", o.get("id"));
            item.put("command", o.get("command"));
            item.put("status", o.get("status"));
            item.put("outcome", o.get("outcome"));
            item.put("startedAt", instant(o.get("started_at")));
            item.put("executedBy", o.get("executed_by"));
            item.put("environment", o.get("environment"));
            item.put("connection", o.get("connection"));
            item.put("errorMessage", o.get("error_message"));
            return item;
        }).toList());

        out.put("findings", jdbc.sql("""
                select pc.code as check_code, f.severity, f.result, f.message, f.object_name, f.created_at, f.operation_id
                  from policy_finding f join policy_check pc on pc.id = f.check_id
                 where f.changeset_id = :id
                 order by f.created_at desc limit 50
                """).param("id", id).query().listOfRows().stream().map(f -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("check", f.get("check_code"));
            item.put("severity", f.get("severity"));
            item.put("result", f.get("result"));
            item.put("message", f.get("message"));
            item.put("objectName", f.get("object_name"));
            item.put("at", instant(f.get("created_at")));
            item.put("operationId", f.get("operation_id"));
            return item;
        }).toList());

        out.put("rollbacks", jdbc.sql("""
                select r.id, r.status, r.reason, r.requested_by, r.approved_by, r.created_at, r.operation_id, e.code as environment
                  from rollback_request r
                  join db_connection c on c.id = r.db_connection_id
                  join environment e on e.id = c.environment_id
                 where r.changeset_id = :id
                 order by r.created_at desc limit 50
                """).param("id", id).query().listOfRows().stream().map(b -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", b.get("id"));
            item.put("status", b.get("status"));
            item.put("reason", b.get("reason"));
            item.put("requestedBy", b.get("requested_by"));
            item.put("approvedBy", b.get("approved_by"));
            item.put("at", instant(b.get("created_at")));
            item.put("operationId", b.get("operation_id"));
            item.put("environment", b.get("environment"));
            return item;
        }).toList());
        return out;
    }

    /**
     * The version each database ran against the one the repository has now, with the lines that differ. A changeset edited after it ran
     * shows up here before the deploy refuses it.
     */
    @GetMapping("/changesets/{id}/versions")
    List<Map<String, Object>> versions(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID id) {
        return checksums.versions(workspaces.resolve(ws), id);
    }

    // ----------------------------------------------------------------- source

    private static final java.util.regex.Pattern COMMIT = java.util.regex.Pattern.compile("[0-9a-f]{7,40}");
    private static final java.util.regex.Pattern REF = java.util.regex.Pattern.compile("[0-9A-Za-z._/-]{1,100}");

    /**
     * What the changeset says, as it is in the repository: the file it lives in, with the lines of the changeset (and its rollback)
     * marked. This is what a person reads before approving.
     *
     * <p>Where it is read from: {@code ref} when given; otherwise the default branch first (what a promotion will run), then the
     * commits of the operations that evaluated it, newest first (a changeset that is still on a branch has only those). The answer
     * is always 200: when the file cannot be read, {@code found} is false and {@code reason} says why, so the screen can tell the
     * person what to fix instead of showing an error.
     */
    @GetMapping("/changesets/{id}/source")
    Map<String, Object> source(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID id,
                               @RequestParam(required = false) String ref) {
        UUID workspace = workspaces.resolve(ws);
        List<Map<String, Object>> found = jdbc.sql("""
                select cs.cs_id, cs.author, cs.file_path, cl.path as changelog_path, p.repo_url, p.default_branch
                  from changeset cs join changelog cl on cl.id = cs.changelog_id join project p on p.id = cl.project_id
                 where cs.id = :id and p.workspace_id = :ws
                """).param("id", id).param("ws", workspace).query().listOfRows();
        if (found.isEmpty()) {
            throw LqmException.notFound("changeset");
        }
        Map<String, Object> r = found.get(0);
        String file = (String) r.get("file_path");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", false);
        out.put("path", file);
        out.put("format", formatOf(file));

        String repo = (String) r.get("repo_url");
        if (repo == null || repo.isBlank()) {
            out.put("reason", "NO_REPOSITORY");
            return out;
        }
        if (!files.configured()) {
            out.put("reason", "NOT_CONFIGURED");
            return out;
        }
        if (blank(ref) != null && (!REF.matcher(ref.trim()).matches() || ref.contains(".."))) {
            throw LqmException.invalid("ref is not a branch, tag or commit id");
        }
        String project = RepositoryFiles.projectPath(repo);
        List<String> refs = new ArrayList<>();
        if (blank(ref) != null) {
            refs.add(ref.trim());
        } else {
            if (r.get("default_branch") != null) {
                refs.add((String) r.get("default_branch"));
            }
            for (String commit : jdbc.sql("""
                    select o.git_commit
                      from operation_changeset oc join operation o on o.id = oc.operation_id and o.started_at = oc.operation_started_at
                     where oc.changeset_id = :id and o.git_commit is not null
                     group by o.git_commit order by max(o.started_at) desc limit 3
                    """).param("id", id).query(String.class).list()) {
                if (!refs.contains(commit) && COMMIT.matcher(commit).matches()) {
                    refs.add(commit);
                }
            }
        }
        out.put("tried", refs);

        boolean unreachable = false;
        for (String candidateRef : refs) {
            for (String candidate : RepositoryFiles.candidates(file, (String) r.get("changelog_path"))) {
                String text;
                try {
                    text = files.fetch(project, candidate, candidateRef);
                } catch (LqmException e) {
                    unreachable = true;
                    out.put("detail", e.getMessage());
                    break;
                }
                if (text == null) {
                    continue;
                }
                out.put("found", true);
                out.remove("reason");
                out.put("path", candidate);
                out.put("ref", candidateRef);
                out.put("refKind", COMMIT.matcher(candidateRef).matches() && !candidateRef.equals(r.get("default_branch")) ? "COMMIT" : "BRANCH");
                out.put("content", text);
                ChangesetBlocks.find(text, candidate, (String) r.get("cs_id"), (String) r.get("author")).ifPresent(range -> {
                    out.put("startLine", range.start());
                    out.put("endLine", range.end());
                });
                return out;
            }
        }
        out.put("reason", unreachable ? "UNREACHABLE" : "NOT_FOUND");
        return out;
    }

    private static String formatOf(String path) {
        String lower = path == null ? "" : path.toLowerCase();
        if (lower.endsWith(".xml")) {
            return "XML";
        }
        if (lower.endsWith(".yaml") || lower.endsWith(".yml")) {
            return "YAML";
        }
        if (lower.endsWith(".json")) {
            return "JSON";
        }
        return lower.endsWith(".sql") ? "SQL" : "TEXT";
    }

    // ---------------------------------------------------------------- helpers

    private void requireProject(UUID workspace, UUID projectId) {
        if (jdbc.sql("select count(*) from project where id = :id and workspace_id = :ws")
                .param("id", projectId).param("ws", workspace).query(Long.class).single() == 0) {
            throw LqmException.notFound("project");
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Object instant(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : value;
    }
}
