package io.github.carloslimajlle.liquimanager.matrix;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/**
 * The timeline and the promote button. A pipeline DEV, TST, PRD; release-2.1.0 ran in DEV and TST. "ana" may promote
 * to TST only, "bia" to PRD, "carla" may only look.
 */
class PromotionIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String workspace;
    private UUID workspaceId;
    private String project;
    private String pipelineId;
    private String tstStage;
    private String prdStage;
    private String devStage;

    @BeforeEach
    void scenario() {
        GITLAB.requests.clear();
        GITLAB.paths.clear();
        GITLAB.status = 201;
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Promocao\"}".formatted(workspace))).get("id").asString());
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}");
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"TST\",\"name\":\"TST\",\"sortOrder\":2,\"isProduction\":false}");
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"PRD\",\"name\":\"PRD\",\"sortOrder\":3,\"isProduction\":true}");
        project = unique("proj");
        String token = tokens.issue(workspaceId, unique("ing"), List.of("ingest"), null, Instant.now().plusSeconds(3600), "it").token();
        String dev = unique("dev");
        String tst = unique("tst");
        String prd = unique("prd");
        ingest(token, "DEV", dev, Instant.now().minusSeconds(600), "SUP-1");
        ingest(token, "TST", tst, Instant.now().minusSeconds(300), "SUP-1");
        ingest(token, "PRD", prd, Instant.now().minusSeconds(900), "SUP-0");

        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        pipelineId = json(callIn(workspace, "POST", API + "/pipelines",
                "{\"name\":\"%s\",\"projectId\":\"%s\",\"gitlabProjectId\":77,\"gitlabTriggerRef\":\"main\"}"
                        .formatted(unique("PIPE"), projectId))).get("id").asString();
        devStage = addStage(1, "DEV", dev);
        tstStage = addStage(2, "TST", tst);
        prdStage = addStage(3, "PRD", prd);

        bind("ana", "promote:tst");
        bind("bia", "promote:prd");
        bind("carla");
    }

    private void ingest(String token, String env, String connection, Instant at, String changesetId) {
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token,
                payloadAt(env, UUID.randomUUID(), project, connection, uniqueUrl(), "update", "SUCCESS", at,
                        changeset(changesetId, "EXECUTED")));
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    private String addStage(int order, String env, String connectionKey) {
        String environmentId = null;
        for (JsonNode e : json(callIn(workspace, "GET", API + "/environments", null))) {
            if (env.equals(e.get("code").asString())) {
                environmentId = e.get("id").asString();
            }
        }
        UUID connectionId = jdbc.sql("select id from db_connection where slug = :s").param("s", connectionKey)
                .query(UUID.class).single();
        HttpResponse<String> response = callIn(workspace, "POST", API + "/pipelines/" + pipelineId + "/stages",
                "{\"sortOrder\":%d,\"environmentId\":\"%s\",\"dbConnectionId\":\"%s\"}".formatted(order, environmentId, connectionId));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    private void bind(String user, String... permissions) {
        String principal = null;
        for (JsonNode found : json(callIn(workspace, "GET", API + "/principals?q=" + user, null))) {
            if (user.equals(found.get("externalId").asString())) {
                principal = found.get("id").asString();
            }
        }
        if (principal == null) {
            principal = json(callIn(workspace, "POST", API + "/principals",
                    "{\"type\":\"USER\",\"externalId\":\"%s\",\"displayName\":\"%s\"}".formatted(user, user))).get("id").asString();
        }
        StringBuilder perms = new StringBuilder();
        for (String permission : permissions) {
            perms.append('"').append(permission).append("\",");
        }
        HttpResponse<String> response = callIn(workspace, "POST", API + "/access/bindings",
                "{\"principalId\":\"%s\",\"permissions\":[%s\"assignment:view\"],\"scopeType\":\"WORKSPACE\"}"
                        .formatted(principal, perms));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private HttpResponse<String> promote(String user, String stage, String body) {
        return callAs(user, workspace, "POST", API + "/pipelines/" + pipelineId + "/stages/" + stage + "/promote", body);
    }

    // ------------------------------------------------------------------ promote

    @Test
    @DisplayName("promoting fires the GitLab trigger with the target environment and the release, and records it")
    void triggersGitlab() {
        HttpResponse<String> response = promote("ana", tstStage, "{\"release\":\"release-2.1.0\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode promotion = json(response);
        assertThat(promotion.get("status").asString()).isEqualTo("TRIGGERED");
        assertThat(promotion.get("environment").asString()).isEqualTo("TST");
        assertThat(promotion.get("gitlabPipeline").asString()).isEqualTo("4242");
        assertThat(promotion.get("gitlabUrl").asString()).isEqualTo("http://gitlab.test/p/-/pipelines/4242");

        assertThat(GITLAB.paths).containsExactly("/api/v4/projects/77/trigger/pipeline");
        assertThat(GITLAB.requests.get(0)).contains("token=glptt-SECRET", "ref=main", "variables[LQM_PROMOTE_TO]=TST",
                "variables[LQM_PROMOTE_RELEASE]=release-2.1.0", "variables[LQM_PROMOTE_BY]=ana");
        assertThat(response.body()).as("the trigger token is never echoed").doesNotContain("glptt-SECRET");
    }

    @Test
    @DisplayName("the first stage has nothing to be promoted from")
    void firstStage() {
        HttpResponse<String> response = promote("ana", devStage, null);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(GITLAB.requests).isEmpty();
    }

    @Test
    @DisplayName("the caller needs promote:<environment> for the stage being promoted to")
    void needsPermission() {
        assertThat(promote("ana", prdStage, null).statusCode()).as("ana may promote to TST only").isEqualTo(403);
        assertThat(promote("carla", tstStage, null).statusCode()).as("carla may only look").isEqualTo(403);
        assertThat(promote("bia", prdStage, null).statusCode()).isEqualTo(201);
        assertThat(GITLAB.requests).hasSize(1);
    }

    @Test
    @DisplayName("a GitLab refusal is a 502 with the status only, and the attempt is recorded as FAILED")
    void gitlabRefuses() {
        GITLAB.status = 500;

        HttpResponse<String> response = promote("ana", tstStage, null);

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(json(response).get("code").asString()).isEqualTo("LQM-070");
        assertThat(response.body()).contains("GitLab answered 500").doesNotContain("glptt-SECRET");
        assertThat(jdbc.sql("select status from promotion where pipeline_id = :p").param("p", UUID.fromString(pipelineId))
                .query(String.class).list()).containsExactly("FAILED");
    }

    @Test
    @DisplayName("a pipeline without a GitLab project cannot be promoted")
    void notConfigured() {
        String bare = json(callIn(workspace, "POST", API + "/pipelines", "{\"name\":\"" + unique("BARE") + "\"}")).get("id").asString();
        String env = null;
        for (JsonNode e : json(callIn(workspace, "GET", API + "/environments", null))) {
            if ("TST".equals(e.get("code").asString())) {
                env = e.get("id").asString();
            }
        }
        UUID connection = jdbc.sql("select db_connection_id from pipeline_stage where id = :s")
                .param("s", UUID.fromString(tstStage)).query(UUID.class).single();
        callIn(workspace, "POST", API + "/pipelines/" + bare + "/stages",
                "{\"sortOrder\":1,\"environmentId\":\"%s\",\"dbConnectionId\":\"%s\"}".formatted(env, connection));
        String second = json(callIn(workspace, "POST", API + "/pipelines/" + bare + "/stages",
                "{\"sortOrder\":2,\"environmentId\":\"%s\",\"dbConnectionId\":\"%s\"}".formatted(env, connection))).get("id").asString();

        HttpResponse<String> response = callAs("ana", workspace, "POST", API + "/pipelines/" + bare + "/stages/" + second + "/promote", null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
        assertThat(GITLAB.requests).isEmpty();
    }

    // ----------------------------------------------------------------- timeline

    @Test
    @DisplayName("the timeline lists the deploys with their release, newest first, and the promotions among them")
    void timeline() {
        promote("ana", tstStage, "{\"release\":\"release-2.1.0\"}");

        JsonNode events = json(callIn(workspace, "GET", API + "/pipelines/" + pipelineId + "/timeline", null));

        assertThat(events.size()).isEqualTo(4);
        assertThat(events.get(0).get("kind").asString()).as("the promotion just happened").isEqualTo("PROMOTION");
        assertThat(events.get(0).get("environment").asString()).isEqualTo("TST");
        assertThat(events.get(0).get("by").asString()).isEqualTo("ana");
        JsonNode newestDeploy = events.get(1);
        assertThat(newestDeploy.get("kind").asString()).isEqualTo("DEPLOY");
        assertThat(newestDeploy.get("environment").asString()).isEqualTo("TST");
        assertThat(newestDeploy.get("release").asString()).isEqualTo("release-2.1.0");
        assertThat(newestDeploy.get("changesets").asInt()).isEqualTo(1);
        assertThat(newestDeploy.get("operationId").asString()).isNotBlank();
        assertThat(events.get(2).get("environment").asString()).isEqualTo("DEV");
        assertThat(events.get(3).get("environment").asString()).isEqualTo("PRD");
    }

    @Test
    @DisplayName("a window with nothing in it is an empty list, and a bad date is refused")
    void window() {
        String path = API + "/pipelines/" + pipelineId + "/timeline";

        assertThat(json(callIn(workspace, "GET", path + "?from=2001-01-01&to=2001-01-31", null)).size()).isZero();
        assertThat(callIn(workspace, "GET", path + "?from=ontem", null).statusCode()).isEqualTo(422);
    }

    // ---------------------------------------------------------------- approval

    /** PRD now asks for a second person; the stage keeps everything else it had. */
    private void requireApproval(String stage) {
        JsonNode current = null;
        for (JsonNode found : json(callIn(workspace, "GET", API + "/pipelines/" + pipelineId + "/stages", null))) {
            if (stage.equals(found.get("id").asString())) {
                current = found;
            }
        }
        HttpResponse<String> response = callIn(workspace, "PUT", API + "/pipelines/" + pipelineId + "/stages/" + stage,
                "{\"sortOrder\":%d,\"environmentId\":\"%s\",\"dbConnectionId\":\"%s\",\"requiresApproval\":true}".formatted(
                        current.get("sortOrder").asInt(), current.get("environmentId").asString(), current.get("dbConnectionId").asString()));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }

    private HttpResponse<String> decide(String user, String promotion, String action) {
        return callAs(user, workspace, "POST", API + "/promotions/" + promotion + "/" + action, null);
    }

    private String request(String user) {
        HttpResponse<String> response = promote(user, prdStage, "{\"release\":\"release-2.1.0\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        return json(response).get("id").asString();
    }

    @Test
    @DisplayName("a stage that requires approval only records the request: GitLab is not called until somebody else approves")
    void requestWaits() {
        requireApproval(prdStage);
        HttpResponse<String> response = promote("bia", prdStage, "{\"release\":\"release-2.1.0\"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        JsonNode promotion = json(response);
        assertThat(promotion.get("status").asString()).isEqualTo("REQUESTED");
        assertThat(promotion.get("requestedBy").asString()).isEqualTo("bia");
        assertThat(promotion.has("gitlabPipeline")).as("no GitLab run yet").isFalse();
        assertThat(GITLAB.requests).as("nothing was fired").isEmpty();
        assertThat(jdbc.sql("select count(*) from audit_event where action = 'pipeline.promote.request' and target_id = :id")
                .param("id", promotion.get("id").asString()).query(Long.class).single()).isEqualTo(1);

        JsonNode pending = json(callIn(workspace, "GET", API + "/promotions?pipeline=" + pipelineId + "&status=REQUESTED", null));
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).get("id").asString()).isEqualTo(promotion.get("id").asString());
    }

    @Test
    @DisplayName("whoever asked cannot approve (SoD-2); another person with promote:<env> can, and only then does GitLab get called")
    void approvalNeedsASecondPerson() {
        requireApproval(prdStage);
        bind("dora", "promote:prd");
        String promotion = request("bia");

        HttpResponse<String> self = decide("bia", promotion, "approve");
        assertThat(self.statusCode()).as(self.body()).isEqualTo(409);
        assertThat(self.body()).contains("LQM-010");
        assertThat(decide("ana", promotion, "approve").statusCode()).as("ana may promote to TST only").isEqualTo(403);
        assertThat(decide("carla", promotion, "approve").statusCode()).as("carla may only look").isEqualTo(403);
        assertThat(GITLAB.requests).isEmpty();

        HttpResponse<String> approved = decide("dora", promotion, "approve");
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        JsonNode body = json(approved);
        assertThat(body.get("status").asString()).isEqualTo("TRIGGERED");
        assertThat(body.get("decidedBy").asString()).isEqualTo("dora");
        assertThat(body.get("gitlabPipeline").asString()).isEqualTo("4242");
        assertThat(GITLAB.requests).hasSize(1);
        assertThat(GITLAB.requests.get(0)).contains("variables[LQM_PROMOTE_TO]=PRD", "variables[LQM_PROMOTE_RELEASE]=release-2.1.0",
                "variables[LQM_PROMOTE_BY]=bia");

        assertThat(decide("dora", promotion, "approve").statusCode()).as("decided once").isEqualTo(409);
        assertThat(GITLAB.requests).as("a second approval does not fire again").hasSize(1);
        assertThat(jdbc.sql("select count(*) from audit_event where action = 'pipeline.promote.approve' and target_id = :id")
                .param("id", promotion).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    @DisplayName("rejecting ends the request without calling GitLab; only the requester can cancel")
    void rejectAndCancel() {
        requireApproval(prdStage);
        bind("dora", "promote:prd");
        String rejected = request("bia");
        assertThat(decide("carla", rejected, "reject").statusCode()).as("needs promote:prd").isEqualTo(403);
        HttpResponse<String> response = decide("dora", rejected, "reject");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("status").asString()).isEqualTo("REJECTED");
        assertThat(decide("dora", rejected, "approve").statusCode()).as("a rejected request cannot be approved").isEqualTo(409);

        String cancelled = request("bia");
        assertThat(decide("dora", cancelled, "cancel").statusCode()).as("not the requester").isEqualTo(403);
        HttpResponse<String> own = decide("bia", cancelled, "cancel");
        assertThat(own.statusCode()).as(own.body()).isEqualTo(200);
        assertThat(json(own).get("status").asString()).isEqualTo("CANCELED");
        assertThat(GITLAB.requests).isEmpty();
    }

    @Test
    @DisplayName("when GitLab refuses at approval time the promotion is FAILED with the reason, and the answer is a 502")
    void gitlabRefusesAtApproval() {
        requireApproval(prdStage);
        bind("dora", "promote:prd");
        String promotion = request("bia");
        GITLAB.status = 400;

        HttpResponse<String> response = decide("dora", promotion, "approve");
        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).doesNotContain("glptt-SECRET");
        JsonNode stored = json(callIn(workspace, "GET", API + "/promotions?pipeline=" + pipelineId + "&status=FAILED", null)).get(0);
        assertThat(stored.get("id").asString()).isEqualTo(promotion);
        assertThat(stored.get("decidedBy").asString()).isEqualTo("dora");
        assertThat(stored.get("error").asString()).isEqualTo("GitLab answered 400");
    }
}
