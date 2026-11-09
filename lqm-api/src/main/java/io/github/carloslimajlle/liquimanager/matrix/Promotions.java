package io.github.carloslimajlle.liquimanager.matrix;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What a promotion is, in the database and at GitLab: the row, how it is shown, and the pipeline trigger that makes it real.
 *
 * <p>Two paths end in the trigger: a stage that does not require approval fires it when somebody asks; one that does
 * waits as {@code REQUESTED} until a second person approves ({@link PromotionDecisionController}). Both go through
 * {@link #fire}, so the variables GitLab receives, and what is recorded when it refuses, are the same.
 */
@Component
class Promotions {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SELECT = """
            select id, pipeline_id, stage_id, environment, release, status, requested_by, decided_by, decided_at,
                   gitlab_pipeline, gitlab_url, error, created_at, workspace_id
              from promotion
            """;

    private final JdbcClient jdbc;
    private final AuditService audit;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String gitlabUrl;
    private final String triggerToken;

    Promotions(JdbcClient jdbc, AuditService audit, @Value("${lqm.gitlab.base-url:}") String gitlabUrl,
               @Value("${lqm.gitlab.trigger-token:}") String triggerToken) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.gitlabUrl = gitlabUrl;
        this.triggerToken = triggerToken;
    }

    boolean configured() {
        return !gitlabUrl.isBlank() && !triggerToken.isBlank();
    }

    // ------------------------------------------------------------------ the row

    Map<String, Object> find(UUID workspace, UUID id) {
        return jdbc.sql(SELECT + " where id = :id and workspace_id = :ws").param("id", id).param("ws", workspace)
                .query().listOfRows().stream().findFirst().map(Promotions::present)
                .orElseThrow(() -> LqmException.notFound("promotion"));
    }

    static Map<String, Object> present(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.get("id"));
        out.put("pipelineId", row.get("pipeline_id"));
        out.put("stageId", row.get("stage_id"));
        out.put("environment", row.get("environment"));
        out.put("release", row.get("release"));
        out.put("status", row.get("status"));
        out.put("requestedBy", row.get("requested_by"));
        out.put("decidedBy", row.get("decided_by"));
        out.put("decidedAt", row.get("decided_at") == null ? null : ((Timestamp) row.get("decided_at")).toInstant().toString());
        out.put("gitlabPipeline", row.get("gitlab_pipeline"));
        out.put("gitlabUrl", row.get("gitlab_url"));
        out.put("error", row.get("error"));
        out.put("createdAt", ((Timestamp) row.get("created_at")).toInstant().toString());
        return out;
    }

    /** Records a request that waits for a second person. */
    UUID insertRequested(UUID workspace, UUID pipeline, UUID stage, String env, String release, String requestedBy,
                         UUID id) {
        jdbc.sql("""
                insert into promotion (id, workspace_id, pipeline_id, stage_id, environment, release, status, requested_by)
                values (:id, :ws, :pipeline, :stage, :env, :release, 'REQUESTED', :by)
                """).param("id", id).param("ws", workspace).param("pipeline", pipeline).param("stage", stage)
                .param("env", env).param("release", release).param("by", requestedBy).update();
        return id;
    }

    // ------------------------------------------------------------------ the trigger

    /** What GitLab answered, or why it did not. */
    record Fired(String pipeline, String webUrl, String error) {
        boolean ok() {
            return error == null;
        }
    }

    /**
     * {@code POST /projects/:id/trigger/pipeline}. A refusal carries the HTTP status and nothing else: GitLab can echo the
     * token back in an error body.
     */
    Fired fire(long project, String ref, String env, UUID stageId, String release, String actor) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", triggerToken);
        form.put("ref", ref);
        form.put("variables[LQM_PROMOTE_TO]", env);
        form.put("variables[LQM_PROMOTE_STAGE]", stageId.toString());
        form.put("variables[LQM_PROMOTE_BY]", actor);
        if (release != null) {
            form.put("variables[LQM_PROMOTE_RELEASE]", release);
        }
        StringBuilder encoded = new StringBuilder();
        form.forEach((k, v) -> {
            if (!encoded.isEmpty()) {
                encoded.append('&');
            }
            encoded.append(URLEncoder.encode(k, StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(v, StandardCharsets.UTF_8));
        });
        String base = gitlabUrl.endsWith("/") ? gitlabUrl.substring(0, gitlabUrl.length() - 1) : gitlabUrl;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v4/projects/" + project + "/trigger/pipeline"))
                    .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(encoded.toString())).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return new Fired(null, null, "GitLab answered " + response.statusCode());
            }
            JsonNode answer = JSON.readTree(response.body());
            return new Fired(answer.hasNonNull("id") ? answer.get("id").asString() : null,
                    answer.hasNonNull("web_url") ? answer.get("web_url").asString() : null, null);
        } catch (IOException | IllegalArgumentException e) {
            return new Fired(null, null, "GitLab could not be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Fired(null, null, "GitLab could not be reached");
        }
    }

    /** The failure the API answers when GitLab refused: the row already says FAILED and why. */
    static LqmException gitlabRefused(String error) {
        return new LqmException(HttpStatus.BAD_GATEWAY, "LQM-070", error);
    }
}
