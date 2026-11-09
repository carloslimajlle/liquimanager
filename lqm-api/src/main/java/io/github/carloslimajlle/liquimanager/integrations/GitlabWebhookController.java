package io.github.carloslimajlle.liquimanager.integrations;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.carloslimajlle.liquimanager.changesets.InReviewService;
import io.github.carloslimajlle.liquimanager.common.Api;
import io.github.carloslimajlle.liquimanager.security.ServiceToken;
import io.github.carloslimajlle.liquimanager.security.ServiceTokenAuthentication;
import tools.jackson.databind.JsonNode;

/**
 * What GitLab tells LiquiManager about merge requests (token scope {@code webhook}, sent by GitLab in {@code X-Gitlab-Token}).
 *
 * <p>One endpoint for the whole workspace, so it is registered ONCE in GitLab (a group webhook, or a system hook on a self-managed
 * instance) instead of in every project: the payload names the project, and LiquiManager finds its own by the repository path.
 *
 * <p>Only two things matter here. A merge request CLOSED closes its group in the "In review" tab (the changesets only that merge request
 * had), and one REOPENED reopens it. A merge request MERGED needs nothing: the next report from the default branch brings its changesets
 * into the changelog. GitLab sends nothing when a merge request is DELETED, so that case is still a person's to close.
 *
 * <p>It always answers 200 to what it does not understand or care about (an unknown project, another kind of event): GitLab disables a
 * webhook that keeps failing, and one project we do not know must not take the others down with it.
 */
@RestController
@RequestMapping(Api.V1 + "/integrations/gitlab")
class GitlabWebhookController {

    private static final Logger LOG = LoggerFactory.getLogger(GitlabWebhookController.class);

    private final JdbcClient jdbc;
    private final InReviewService review;

    GitlabWebhookController(JdbcClient jdbc, InReviewService review) {
        this.jdbc = jdbc;
        this.review = review;
    }

    @PostMapping("/webhook")
    Map<String, Object> webhook(Authentication authentication, @RequestBody JsonNode event) {
        ServiceToken token = ((ServiceTokenAuthentication) authentication).getPrincipal();
        Map<String, Object> out = new LinkedHashMap<>();

        if (!"merge_request".equals(event.path("object_kind").asString(""))) {
            out.put("handled", false);
            out.put("reason", "not a merge request event");
            return out;
        }
        String action = event.path("object_attributes").path("action").asString("");
        String iid = event.path("object_attributes").path("iid").asString("");
        String path = event.path("project").path("path_with_namespace").asString("");
        String user = event.path("user").path("username").asString("gitlab");
        out.put("action", action);
        out.put("mergeRequest", iid);

        if (iid.isBlank() || path.isBlank()) {
            out.put("handled", false);
            return out;
        }
        List<UUID> projects = projectsOf(token, path);
        if (projects.isEmpty()) {
            LOG.info("gitlab webhook: no project of the workspace has the repository {}", path);
            out.put("handled", false);
            out.put("reason", "no project of this workspace has that repository");
            return out;
        }
        String actor = "gitlab:" + user;
        // The state is kept whatever the action: a merge request closed before its pipeline reported has nothing to close yet, and the
        // report that comes later has to know it was closed.
        // The state comes from the ACTION, never from the payload's "state" or "updated_at": GitLab builds a payload when its job runs, not
        // when the thing happened, so the event of a merge request's creation can carry "opened" and a time later than the close that
        // came right after it. Only close, reopen and merge are transitions; the creation only says "opened" when nothing is known yet.
        Instant at = eventTime(event.path("object_attributes"));
        boolean latest = true;
        for (UUID project : projects) {
            switch (action) {
                case "open" -> review.recordOpened(project, iid, actor);
                case "close" -> latest &= review.recordState(project, iid, "closed", actor, at);
                case "reopen" -> latest &= review.recordState(project, iid, "opened", actor, at);
                case "merge" -> latest &= review.recordState(project, iid, "merged", actor, at);
                default -> {
                    // update, approved...: they say nothing the transitions above do not
                }
            }
        }
        if (!(action.equals("close") || action.equals("reopen"))) {
            out.put("handled", false);
            return out;
        }
        if (!latest) {
            // GitLab delivers asynchronously: this event was overtaken by a newer one about the same merge request.
            out.put("handled", false);
            out.put("reason", "a newer event about this merge request was already received");
            return out;
        }
        int changed = 0;
        for (UUID project : projects) {
            changed += action.equals("close")
                    ? review.close(token.workspaceId(), project, iid, null, "Encerrado automaticamente: o MR !" + iid + " foi fechado no GitLab por " + user + ".", actor)
                    : review.reopen(token.workspaceId(), project, iid, null, actor);
        }
        out.put("handled", true);
        out.put("changesets", changed);
        return out;
    }

    /** When the merge request was last updated, as the event says ("2026-11-05 00:37:30 UTC", or ISO); now when it does not say. */
    static Instant eventTime(JsonNode attributes) {
        String raw = attributes.path("updated_at").asString("").trim();
        if (!raw.isEmpty()) {
            for (java.util.function.Function<String, Instant> parse : List.<java.util.function.Function<String, Instant>>of(
                    value -> java.time.LocalDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'"))
                            .toInstant(java.time.ZoneOffset.UTC),
                    value -> java.time.OffsetDateTime.parse(value, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")).toInstant(),
                    value -> java.time.OffsetDateTime.parse(value).toInstant())) {
                try {
                    return parse.apply(raw);
                } catch (java.time.DateTimeException e) {
                    // try the next format
                }
            }
        }
        return Instant.now();
    }

    /** The workspace's projects whose repository is {@code path} on GitLab ("group/project"), whatever host and scheme the URL has. */
    private List<UUID> projectsOf(ServiceToken token, String path) {
        List<UUID> found = jdbc.sql("""
                select id from project
                 where workspace_id = :ws and repo_url is not null
                   and right(lower(regexp_replace(repo_url, '(\\.git)?/*$', '')), length(:suffix)) = :suffix
                """).param("ws", token.workspaceId()).param("suffix", "/" + path.toLowerCase(java.util.Locale.ROOT))
                .query(UUID.class).list();
        // A token limited to some projects speaks only for them.
        return token.projectIds() == null || token.projectIds().isEmpty() ? found : found.stream().filter(token.projectIds()::contains).toList();
    }
}
