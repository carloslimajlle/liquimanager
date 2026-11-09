package io.github.carloslimajlle.liquimanager.changesets;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.carloslimajlle.liquimanager.audit.AuditService;

/**
 * Closing and reopening a group of changesets that only a merge request has (see {@link InReviewController}). Used by the screen, where
 * a person does it with a note, and by GitLab's webhook, which does it when the merge request is closed or reopened there.
 */
@Service
public class InReviewService {

    /** A group is the merge request when the pipeline said which, else the branch. */
    private static final String MATCH = """
            ((cast(:iid as text) is not null and origin_mr_iid = :iid)
              or (cast(:iid as text) is null and origin_mr_iid is null and origin_branch is not distinct from cast(:branch as text)))""";

    private final JdbcClient jdbc;
    private final AuditService audit;

    InReviewService(JdbcClient jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    /** How many changesets were closed (0: nothing was open in that group). */
    @Transactional
    public int close(UUID workspace, UUID projectId, String mrIid, String branch, String note, String actor) {
        int closed = jdbc.sql("""
                update changeset set dismissed_at = now(), dismissed_by = :by, dismissed_note = :note
                 where changelog_id in (select id from changelog where project_id = :project)
                   and seen_on_default_at is null and dismissed_at is null
                   and not exists (select 1 from changeset_state held where held.changeset_id = changeset.id and held.status = 'EXECUTED')
                   and %s
                """.formatted(MATCH)).param("by", actor).param("note", note).param("project", projectId)
                .param("iid", blank(mrIid)).param("branch", blank(branch)).update();
        if (closed > 0) {
            audit.record(workspace, actor, "changeset.review.close", "project", projectId.toString(), null,
                    Map.of("group", describe(mrIid, branch), "changesets", closed, "note", note));
        }
        return closed;
    }

    /** How many changesets were reopened (0: nothing was closed in that group). */
    @Transactional
    public int reopen(UUID workspace, UUID projectId, String mrIid, String branch, String actor) {
        int reopened = jdbc.sql("""
                update changeset set dismissed_at = null, dismissed_by = null, dismissed_note = null
                 where changelog_id in (select id from changelog where project_id = :project)
                   and seen_on_default_at is null and dismissed_at is not null and %s
                """.formatted(MATCH)).param("project", projectId).param("iid", blank(mrIid)).param("branch", blank(branch)).update();
        if (reopened > 0) {
            audit.record(workspace, actor, "changeset.review.reopen", "project", projectId.toString(), null,
                    Map.of("group", describe(mrIid, branch), "changesets", reopened));
        }
        return reopened;
    }

    /** The creation of a merge request: it is "opened" only if nothing else is known (a close that overtook it stays), and any real transition wins over it. */
    @Transactional
    public void recordOpened(UUID projectId, String mrIid, String by) {
        jdbc.sql("""
                insert into gitlab_merge_request (project_id, iid, state, changed_by, changed_at) values (:p, :iid, 'opened', :by, :at)
                on conflict (project_id, iid) do nothing
                """).param("p", projectId).param("iid", mrIid).param("by", by)
                .param("at", java.time.OffsetDateTime.ofInstant(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC)).update();
    }

    /**
     * Remembers a TRANSITION of a merge request (closed, reopened, merged), ordered by the time its event says it happened: GitLab delivers
     * its webhooks asynchronously, so a "reopen" can reach us before the "close" it follows. Returns false when a newer transition was
     * already known: this one is stale and must not be acted on.
     *
     * <p>Within the same instant "closed" wins: closing right after opening is far more likely than the opposite.
     */
    @Transactional
    public boolean recordState(UUID projectId, String mrIid, String state, String by, java.time.Instant at) {
        return jdbc.sql("""
                insert into gitlab_merge_request (project_id, iid, state, changed_by, changed_at) values (:p, :iid, :state, :by, :at)
                on conflict (project_id, iid) do update
                   set state = excluded.state, changed_by = excluded.changed_by, changed_at = excluded.changed_at
                 where gitlab_merge_request.changed_at < excluded.changed_at
                    or (gitlab_merge_request.changed_at = excluded.changed_at and excluded.state = 'closed')
                """).param("p", projectId).param("iid", mrIid).param("state", state).param("by", by)
                .param("at", java.time.OffsetDateTime.ofInstant(at, java.time.ZoneOffset.UTC)).update() > 0;
    }

    private static String describe(String mrIid, String branch) {
        return blank(mrIid) != null ? "MR !" + mrIid : "branch " + branch;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
