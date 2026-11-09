package io.github.carloslimajlle.liquimanager.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/**
 * A binding on a CONNECTION, a PIPELINE or an ENVIRONMENT decides writes about THAT resource and nothing else (docs/09 § 2).
 * The case that matters most is the one that would be a hole: a permission granted for DEV must not be good for PRD.
 *
 * <p>Two environments (DEV, PRD), a connection in each, and two pipelines (each DEV then PRD), with a GitLab trigger.
 */
class ScopedPermissionsIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String workspace;
    private String devEnv;
    private String prdEnv;
    private String devConnection;
    private String prdConnection;
    private String pipelineOne;
    private String pipelineTwo;
    private String prdStageOne;

    private String body(String slug, String env, String name) {
        return """
                {"environmentId":"%s","slug":"%s","displayName":"%s","engine":"POSTGRESQL","jdbcUrl":"jdbc:postgresql://h/db"}
                """.formatted(env, slug, name);
    }

    @BeforeEach
    void scenario() {
        GITLAB.requests.clear();
        GITLAB.paths.clear();
        GITLAB.status = 201;
        workspace = unique("ws");
        assertThat(post(API + "/workspaces", "{\"slug\":\"%s\",\"name\":\"Escopos\"}".formatted(workspace)).statusCode()).isEqualTo(201);
        devEnv = json(callIn(workspace, "POST", API + "/environments",
                "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}")).get("id").asString();
        prdEnv = json(callIn(workspace, "POST", API + "/environments",
                "{\"code\":\"PRD\",\"name\":\"PRD\",\"sortOrder\":2,\"isProduction\":true}")).get("id").asString();
        devConnection = json(callIn(workspace, "POST", API + "/connections", body(unique("cdev"), devEnv, "dev"))).get("id").asString();
        prdConnection = json(callIn(workspace, "POST", API + "/connections", body(unique("cprd"), prdEnv, "prd"))).get("id").asString();

        String project = json(callIn(workspace, "POST", API + "/projects", "{\"slug\":\"%s\",\"name\":\"P\"}".formatted(unique("p")))).get("id").asString();
        pipelineOne = pipeline(project, "one");
        pipelineTwo = pipeline(project, "two");
        prdStageOne = stage(pipelineOne, 1, devEnv, devConnection);
        stage(pipelineOne, 2, prdEnv, prdConnection);
        stage(pipelineTwo, 1, devEnv, devConnection);
        // the PRD stage is the second one
        prdStageOne = stageIdOf(pipelineOne, prdEnv);
    }

    private String pipeline(String project, String name) {
        HttpResponse<String> response = callIn(workspace, "POST", API + "/pipelines",
                "{\"name\":\"%s\",\"projectId\":\"%s\",\"gitlabProjectId\":77,\"gitlabTriggerRef\":\"main\"}".formatted(unique(name), project));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    private String stage(String pipeline, int order, String env, String connection) {
        HttpResponse<String> response = callIn(workspace, "POST", API + "/pipelines/" + pipeline + "/stages",
                "{\"sortOrder\":%d,\"environmentId\":\"%s\",\"dbConnectionId\":\"%s\"}".formatted(order, env, connection));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    private String stageIdOf(String pipeline, String env) {
        for (JsonNode stage : json(callIn(workspace, "GET", API + "/pipelines/" + pipeline + "/stages", null))) {
            if (env.equals(stage.get("environmentId").asString())) {
                return stage.get("id").asString();
            }
        }
        throw new AssertionError("no stage");
    }

    /** Binds {@code user} to {@code permissions} (and the denied ones) in a scope; production scopes need an approver and a deadline. */
    private void grant(String user, String scopeType, String scopeId, List<String> permissions, List<String> denied) {
        String principal = json(callIn(workspace, "POST", API + "/principals",
                "{\"type\":\"USER\",\"externalId\":\"%s\",\"displayName\":\"%s\"}".formatted(user, user))).get("id").asString();
        String quoted = permissions.stream().map(p -> "\"" + p + "\"").collect(Collectors.joining(","));
        String deniedQuoted = denied.stream().map(p -> "\"" + p + "\"").collect(Collectors.joining(","));
        HttpResponse<String> response = callIn(workspace, "POST", API + "/access/bindings", """
                {"principalId":"%s","permissions":[%s,"assignment:view"],"deniedPermissions":[%s],"scopeType":"%s",%s
                 "approver":"chefe","expiresAt":"%s"}
                """.formatted(principal, quoted, deniedQuoted, scopeType,
                scopeId == null ? "" : "\"scopeId\":\"" + scopeId + "\",", Instant.now().plus(30, ChronoUnit.DAYS)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private HttpResponse<String> as(String user, String method, String path, String body) {
        return callAs(user, workspace, method, API + path, body);
    }

    private String rename(String connectionId, String env, String name) {
        return body(unique("c"), env, name);
    }

    // ---------------------------------------------------------------- connections

    @Test
    @DisplayName("connection:edit bound to one connection edits that connection and no other")
    void connectionScope() {
        String user = unique("eva");
        grant(user, "CONNECTION", devConnection, List.of("connection:edit"), List.of());

        assertThat(as(user, "PUT", "/connections/" + devConnection, rename(devConnection, devEnv, "novo")).statusCode()).isEqualTo(200);
        assertThat(as(user, "PUT", "/connections/" + prdConnection, rename(prdConnection, prdEnv, "novo")).statusCode())
                .as("not the other connection").isEqualTo(403);
    }

    @Test
    @DisplayName("an ENVIRONMENT binding covers the connections of that environment")
    void environmentCoversItsConnections() {
        String user = unique("gil");
        grant(user, "ENVIRONMENT", devEnv, List.of("connection:edit"), List.of());

        assertThat(as(user, "PUT", "/connections/" + devConnection, rename(devConnection, devEnv, "novo")).statusCode()).isEqualTo(200);
        assertThat(as(user, "PUT", "/connections/" + prdConnection, rename(prdConnection, prdEnv, "novo")).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("a denial scoped to one connection beats a workspace-wide grant, for that connection only")
    void scopedDenial() {
        String user = unique("hugo");
        grant(user, "WORKSPACE", null, List.of("connection:edit"), List.of());
        // a second binding that only denies, on the PRD connection
        String principal = json(callIn(workspace, "GET", API + "/principals?q=" + user, null)).get(0).get("id").asString();
        HttpResponse<String> deny = callIn(workspace, "POST", API + "/access/bindings", """
                {"principalId":"%s","permissions":[],"deniedPermissions":["connection:edit"],"scopeType":"CONNECTION","scopeId":"%s",
                 "approver":"chefe","expiresAt":"%s"}
                """.formatted(principal, prdConnection, Instant.now().plus(30, ChronoUnit.DAYS)));
        assertThat(deny.statusCode()).as(deny.body()).isEqualTo(201);

        assertThat(as(user, "PUT", "/connections/" + devConnection, rename(devConnection, devEnv, "novo")).statusCode()).isEqualTo(200);
        assertThat(as(user, "PUT", "/connections/" + prdConnection, rename(prdConnection, prdEnv, "novo")).statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------------- pipelines

    @Test
    @DisplayName("pipeline:edit bound to one pipeline edits that pipeline and not the other")
    void pipelineScope() {
        String user = unique("ines");
        grant(user, "PIPELINE", pipelineOne, List.of("pipeline:edit"), List.of());
        String project = json(callIn(workspace, "GET", API + "/pipelines/" + pipelineOne, null)).get("projectId").asString();

        String edit = "{\"name\":\"%s\",\"projectId\":\"%s\"}".formatted(unique("x"), project);
        assertThat(as(user, "PUT", "/pipelines/" + pipelineOne, edit).statusCode()).isEqualTo(200);
        assertThat(as(user, "PUT", "/pipelines/" + pipelineTwo, edit).statusCode()).isEqualTo(403);
    }

    // ------------------------------------------------------------------- promotion

    @Test
    @DisplayName("promote:prd bound to the DEV environment is NOT good for PRD; bound to PRD it is")
    void promotionFollowsTheStagesEnvironment() {
        String wrong = unique("joao");
        String right = unique("kika");
        grant(wrong, "ENVIRONMENT", devEnv, List.of("promote:prd"), List.of());
        grant(right, "ENVIRONMENT", prdEnv, List.of("promote:prd"), List.of());
        String path = "/pipelines/" + pipelineOne + "/stages/" + prdStageOne + "/promote";

        assertThat(as(wrong, "POST", path, "{}").statusCode()).as("a permission for DEV does not reach PRD").isEqualTo(403);
        assertThat(GITLAB.requests).isEmpty();
        HttpResponse<String> ok = as(right, "POST", path, "{}");
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(201);
        assertThat(GITLAB.requests).hasSize(1);
    }

    @Test
    @DisplayName("promote:prd bound to a pipeline promotes in that pipeline only")
    void promotionByPipeline() {
        String user = unique("lia");
        grant(user, "PIPELINE", pipelineOne, List.of("promote:prd"), List.of());

        assertThat(as(user, "POST", "/pipelines/" + pipelineOne + "/stages/" + prdStageOne + "/promote", "{}").statusCode()).isEqualTo(201);
        String otherPrd = stage(pipelineTwo, 2, prdEnv, prdConnection);
        assertThat(as(user, "POST", "/pipelines/" + pipelineTwo + "/stages/" + otherPrd + "/promote", "{}").statusCode()).isEqualTo(403);
    }

    // ----------------------------------------------------------------------- drift

    @Test
    @DisplayName("drift:run bound to one connection is not 403 there, and is 403 on the other")
    void driftByConnection() {
        String user = unique("mara");
        grant(user, "CONNECTION", devConnection, List.of("drift:run"), List.of());

        // the connection has no real database behind it: what matters is whether the rule let the request in
        assertThat(as(user, "POST", "/connections/" + devConnection + "/drift-runs", null).statusCode()).isNotEqualTo(403);
        assertThat(as(user, "POST", "/connections/" + prdConnection + "/drift-runs", null).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("nobody gets a scoped permission by naming a resource that does not exist or is not theirs")
    void unknownResourcesFallBackToTheWorkspace() {
        String user = unique("nuno");
        grant(user, "CONNECTION", devConnection, List.of("connection:edit"), List.of());
        String missing = UUID.randomUUID().toString();
        assertThat(as(user, "PUT", "/connections/" + missing, rename(missing, devEnv, "x")).statusCode()).isEqualTo(403);
    }
}
