package io.github.carloslimajlle.liquimanager.changesets;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.catalog.WorkspaceContext;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.common.Labels;
import io.github.carloslimajlle.liquimanager.common.LqmException;

/**
 * The changesets that only a merge request or a branch has, grouped by merge request: "in review". They are not part of the changelog
 * until a report from the default branch lists them (then they leave this list by themselves, because they are no longer "only" anywhere).
 *
 * <p>LiquiManager is never told that a merge request was closed or deleted, so a person closes the group here, with a note that says why.
 * Closing marks the changesets (who, when, why); it deletes nothing, can be undone, and undoes itself if the branch reports again.
 */
@RestController
@RequestMapping(Api.V1)
class InReviewController {

    private static final int MIN_NOTE = 5;

    record GroupRef(String mrIid, String branch, String note) {
    }

    private final JdbcClient jdbc;
    private final WorkspaceContext workspaces;
    private final InReviewService service;

    InReviewController(JdbcClient jdbc, WorkspaceContext workspaces, InReviewService service) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.service = service;
    }

    /** The open groups; with {@code closed=true} also the ones a person closed. */
    @GetMapping("/projects/{projectId}/changesets/in-review")
    Map<String, Object> list(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, @PathVariable UUID projectId,
                             @RequestParam(defaultValue = "false") boolean closed) {
        UUID workspace = workspaces.resolve(ws);
        requireProject(workspace, projectId);
        List<Map<String, Object>> rows = jdbc.sql("""
                select cs.id, cs.cs_id, cs.author, cs.file_path, cs.labels, cs.has_rollback, cl.name as changelog,
                       cs.origin_branch, cs.origin_mr_iid, cs.origin_mr_title, cs.origin_mr_url, cs.origin_seen_at, cs.first_seen_at,
                       cs.dismissed_at, cs.dismissed_by, cs.dismissed_note
                  from changeset cs join changelog cl on cl.id = cs.changelog_id
                 where cl.project_id = :project and cs.seen_on_default_at is null
                   and not exists (select 1 from changeset_state held where held.changeset_id = cs.id and held.status = 'EXECUTED')
                 order by coalesce(cs.origin_seen_at, cs.first_seen_at) desc, cs.cs_id
                """).param("project", projectId).query().listOfRows();

        Map<String, Group> groups = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            groups.computeIfAbsent(keyOf((String) row.get("origin_mr_iid"), (String) row.get("origin_branch")), k -> new Group(row)).add(row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int open = 0;
        int closedCount = 0;
        for (Group group : groups.values()) {
            if (group.isClosed()) {
                closedCount++;
            } else {
                open++;
            }
            if (!group.isClosed() || closed) {
                out.add(group.toMap());
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("groups", out);
        body.put("openCount", open);
        body.put("closedCount", closedCount);
        return body;
    }

    /** Closes a group: its changesets stop being listed as in review, with a note that says why. */
    @PostMapping("/projects/{projectId}/changesets/in-review/close")
    @Transactional
    Map<String, Object> close(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, Authentication authentication,
                              @PathVariable UUID projectId, @RequestBody GroupRef body) {
        UUID workspace = workspaces.resolve(ws);
        requireProject(workspace, projectId);
        if (body.note() == null || body.note().trim().length() < MIN_NOTE) {
            throw LqmException.invalid("a note of at least " + MIN_NOTE + " characters is required: say why it is being closed");
        }
        int closed = service.close(workspace, projectId, body.mrIid(), body.branch(), body.note().trim(), AuditService.actorOf(authentication));
        if (closed == 0) {
            throw LqmException.invalid("there is nothing open to close in that group");
        }
        return Map.of("closed", closed);
    }

    /** Undoes a close. */
    @PostMapping("/projects/{projectId}/changesets/in-review/reopen")
    @Transactional
    Map<String, Object> reopen(@RequestHeader(value = WorkspaceContext.HEADER, required = false) String ws, Authentication authentication,
                               @PathVariable UUID projectId, @RequestBody GroupRef body) {
        UUID workspace = workspaces.resolve(ws);
        requireProject(workspace, projectId);
        int reopened = service.reopen(workspace, projectId, body.mrIid(), body.branch(), AuditService.actorOf(authentication));
        if (reopened == 0) {
            throw LqmException.invalid("there is nothing closed to reopen in that group");
        }
        return Map.of("reopened", reopened);
    }

    // ------------------------------------------------------------------ helpers

    private static String keyOf(String mrIid, String branch) {
        return mrIid != null ? "mr:" + mrIid : "branch:" + branch;
    }

    private void requireProject(UUID workspace, UUID projectId) {
        if (jdbc.sql("select count(*) from project where id = :id and workspace_id = :ws")
                .param("id", projectId).param("ws", workspace).query(Long.class).single() == 0) {
            throw LqmException.notFound("project");
        }
    }

    /** The changesets of one merge request (or branch). */
    private static final class Group {
        private final String mrIid;
        private final String mrTitle;
        private final String mrUrl;
        private final String branch;
        private final List<Map<String, Object>> changesets = new ArrayList<>();
        private Instant lastSeen;
        private Instant closedAt;
        private String closedBy;
        private String closedNote;
        private int open;

        Group(Map<String, Object> first) {
            this.mrIid = (String) first.get("origin_mr_iid");
            this.mrTitle = (String) first.get("origin_mr_title");
            this.mrUrl = (String) first.get("origin_mr_url");
            this.branch = (String) first.get("origin_branch");
        }

        void add(Map<String, Object> row) {
            Instant seen = instant(row.get("origin_seen_at") != null ? row.get("origin_seen_at") : row.get("first_seen_at"));
            if (lastSeen == null || (seen != null && seen.isAfter(lastSeen))) {
                lastSeen = seen;
            }
            boolean dismissed = row.get("dismissed_at") != null;
            if (dismissed) {
                Instant at = instant(row.get("dismissed_at"));
                if (closedAt == null || at.isAfter(closedAt)) {
                    closedAt = at;
                    closedBy = (String) row.get("dismissed_by");
                    closedNote = (String) row.get("dismissed_note");
                }
            } else {
                open++;
            }
            changesets.add(row);
        }

        boolean isClosed() {
            return open == 0;
        }

        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("mrIid", mrIid);
            out.put("mrTitle", mrTitle);
            out.put("mrUrl", mrUrl);
            out.put("branch", branch);
            out.put("lastSeenAt", lastSeen);
            out.put("closed", isClosed());
            if (isClosed()) {
                out.put("closedAt", closedAt);
                out.put("closedBy", closedBy);
                out.put("closedNote", closedNote);
            }
            // an open group lists what is still open; a closed one lists all it had
            out.put("changesets", changesets.stream().filter(c -> isClosed() || c.get("dismissed_at") == null).map(c -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", c.get("id"));
                item.put("changesetId", c.get("cs_id"));
                item.put("author", c.get("author"));
                item.put("filePath", c.get("file_path"));
                item.put("changelog", c.get("changelog"));
                item.put("release", Labels.release((String) c.get("labels")));
                item.put("hasRollback", c.get("has_rollback"));
                return item;
            }).toList());
            return out;
        }

        private static Instant instant(Object value) {
            return value instanceof Timestamp t ? t.toInstant() : null;
        }
    }
}
