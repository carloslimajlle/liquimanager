package io.github.carloslimajlle.liquimanager.policies;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/**
 * Effective policies and the policy library, against a real PostgreSQL.
 *
 * <p>The precedence tests live in an ISOLATED workspace so they can build packages
 * freely; the shared {@code default} workspace holds the seeded catalogue and must not
 * be touched.
 */
class PoliciesIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String workspace;
    private UUID workspaceId;
    private String token;
    private String project;
    private String projectId;
    private UUID packageDefault;
    private UUID packageChangelog;
    private UUID packageStage;

    @BeforeEach
    void isolatedWorkspace() {
        workspace = unique("ws");
        HttpResponse<String> created = post(API + "/workspaces", "{\"slug\":\"%s\",\"name\":\"Policies\"}".formatted(workspace));
        workspaceId = UUID.fromString(json(created).get("id").asString());
        for (String[] env : new String[][]{{"DEV", "1", "false"}, {"PRD", "2", "true"}}) {
            callIn(workspace, "POST", API + "/environments", """
                    { "code": "%s", "name": "%s", "sortOrder": %s, "isProduction": %s }
                    """.formatted(env[0], env[0], env[1], env[2]));
        }
        token = tokens.issue(workspaceId, unique("pol"), List.of("checks:read"), null,
                Instant.now().plusSeconds(3600), "integration-test").token();

        project = unique("proj");
        projectId = json(callIn(workspace, "POST", API + "/projects",
                "{\"slug\":\"%s\",\"name\":\"P\"}".formatted(project))).get("id").asString();

        packageDefault = packageWith("default", 3, 4, "RollbackRequired");
        packageChangelog = packageWith("pk-changelog", 3, 4, "RollbackRequired", "DropTableWarn");
        packageStage = packageWith("pk-stage", 2, 3, "DropTableWarn");
    }

    // ----------------------------------------------------------------- helpers

    private UUID packageWith(String name, int thresholdProd, int thresholdNonProd, String... codes) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into policy_package (id, workspace_id, name, block_threshold_prod, block_threshold_nonprod)
                values (:id, :ws, :name, :prod, :nonprod)
                """).param("id", id).param("ws", workspaceId).param("name", name)
                .param("prod", thresholdProd).param("nonprod", thresholdNonProd).update();
        for (String code : codes) {
            jdbc.sql("insert into policy_package_check (package_id, check_id) select :pkg, id from policy_check "
                            + "where code = :code and workspace_id is null")
                    .param("pkg", id).param("code", code).update();
        }
        return id;
    }

    private HttpResponse<String> effective(String env, String changelog) {
        String path = API + "/policies/effective?project=" + project + "&env=" + env
                + (changelog == null ? "" : "&changelog=" + changelog);
        return call("GET", path, token, null);
    }

    private JsonNode effectiveBody(String env, String changelog) {
        HttpResponse<String> response = effective(env, changelog);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private static List<String> codes(JsonNode checks) {
        return checks.valueStream().map(c -> c.get("code").asString()).toList();
    }

    private void assignChangelogPackage(String path, UUID packageId) {
        HttpResponse<String> response = callIn(workspace, "POST", API + "/projects/" + projectId + "/changelogs", """
                { "name": "master", "path": "%s", "format": "XML", "policyPackageId": "%s" }
                """.formatted(path, packageId));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    private void assignStagePackage(String envCode, UUID packageId) {
        String pipeline = json(callIn(workspace, "POST", API + "/pipelines",
                "{\"name\":\"%s\",\"projectId\":\"%s\"}".formatted(unique("PIPE"), projectId))).get("id").asString();
        String environmentId = null;
        for (JsonNode e : json(callIn(workspace, "GET", API + "/environments", null))) {
            if (envCode.equals(e.get("code").asString())) {
                environmentId = e.get("id").asString();
            }
        }
        // A stage needs a database; a placeholder connection is enough, nothing connects to it.
        String connection = json(callIn(workspace, "POST", API + "/connections", """
                { "environmentId": "%s", "slug": "%s", "displayName": "c", "engine": "postgresql",
                  "jdbcUrl": "jdbc:postgresql://h:5432/app" }
                """.formatted(environmentId, unique("conn")))).get("id").asString();
        HttpResponse<String> stage = callIn(workspace, "POST", API + "/pipelines/" + pipeline + "/stages", """
                { "sortOrder": 1, "environmentId": "%s", "dbConnectionId": "%s", "policyPackageId": "%s" }
                """.formatted(environmentId, connection, packageId));
        assertThat(stage.statusCode()).as(stage.body()).isEqualTo(201);
    }

    private UUID checkId(String code) {
        return jdbc.sql("select id from policy_check where code = :c and workspace_id is null")
                .param("c", code).query(UUID.class).single();
    }

    // ------------------------------------------------------- the seeded default

    @Test
    @DisplayName("the seeded workspace gets the default package, with the stricter threshold in production")
    void seededDefault() {
        String seededToken = serviceToken("checks:read");

        JsonNode prd = json(call("GET", API + "/policies/effective?project=any&env=PRD", seededToken, null));
        JsonNode dev = json(call("GET", API + "/policies/effective?project=any&env=DEV", seededToken, null));

        assertThat(prd.get("package").asString()).isEqualTo("default");
        assertThat(prd.get("blockThreshold").asInt()).as("CRITICAL blocks in production").isEqualTo(3);
        assertThat(dev.get("blockThreshold").asInt()).as("only BLOCKER blocks elsewhere").isEqualTo(4);
        assertThat(codes(prd.get("checks"))).contains("RollbackRequired", "DropTableWarn", "SecretInChangelog");
        assertThat(prd.get("checks").size()).as("the phase-1 checks marked P").isGreaterThanOrEqualTo(20);
    }

    @Test
    @DisplayName("each check carries its kind and the severity the catalogue gives it")
    void checkShape() {
        JsonNode rollback = null;
        for (JsonNode c : json(call("GET", API + "/policies/effective?project=any&env=PRD", serviceToken("ingest"), null))
                .get("checks")) {
            if ("RollbackRequired".equals(c.get("code").asString())) {
                rollback = c;
            }
        }
        assertThat(rollback).isNotNull();
        assertThat(rollback.get("kind").asString()).isEqualTo("BUILTIN");
        assertThat(rollback.get("severity").asInt()).isEqualTo(2);
    }

    // -------------------------------------------------------------- precedence

    @Test
    @DisplayName("with nothing assigned, the workspace default applies")
    void workspaceDefault() {
        JsonNode body = effectiveBody("PRD", "db/changelog/unknown.xml");

        assertThat(body.get("package").asString()).isEqualTo("default");
        assertThat(codes(body.get("checks"))).containsExactly("RollbackRequired");
    }

    @Test
    @DisplayName("a changelog's own package beats the workspace default")
    void changelogBeatsDefault() {
        assignChangelogPackage("db/changelog/master.xml", packageChangelog);

        JsonNode body = effectiveBody("PRD", "db/changelog/master.xml");

        assertThat(body.get("package").asString()).isEqualTo("pk-changelog");
        assertThat(codes(body.get("checks"))).containsExactly("DropTableWarn", "RollbackRequired");
    }

    @Test
    @DisplayName("a pipeline stage's package beats the changelog's, but only in that stage's environment")
    void stageBeatsChangelog() {
        assignChangelogPackage("db/changelog/master.xml", packageChangelog);
        assignStagePackage("PRD", packageStage);

        assertThat(effectiveBody("PRD", "db/changelog/master.xml").get("package").asString())
                .as("the stage wins in PRD").isEqualTo("pk-stage");
        assertThat(effectiveBody("DEV", "db/changelog/master.xml").get("package").asString())
                .as("no stage in DEV, so the changelog applies").isEqualTo("pk-changelog");
    }

    @Test
    @DisplayName("the threshold comes from the RESOLVED package, so a stage can make production stricter")
    void thresholdFollowsThePackage() {
        assignStagePackage("PRD", packageStage);

        assertThat(effectiveBody("PRD", null).get("blockThreshold").asInt()).isEqualTo(2);
    }

    // --------------------------------------------------- overrides and enabling

    @Test
    @DisplayName("a package's severity and parameter overrides win over the check's own values")
    void overrides() {
        jdbc.sql("update policy_package_check set severity_override = 4, parameters_override = cast(:p as jsonb) "
                        + "where package_id = :pkg and check_id = :chk")
                .param("p", "{\"max\":3}").param("pkg", packageDefault).param("chk", checkId("RollbackRequired")).update();

        JsonNode check = effectiveBody("PRD", null).get("checks").get(0);

        assertThat(check.get("severity").asInt()).as("the package raised it from MAJOR to BLOCKER").isEqualTo(4);
        assertThat(check.get("parameters").get("max").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("a disabled check never reaches the collector")
    void disabledChecksAreLeftOut() {
        UUID custom = UUID.randomUUID();
        jdbc.sql("""
                insert into policy_check (id, workspace_id, code, short_name, category, scope, kind, default_severity,
                                          parameters_schema, message_template, enabled)
                values (:id, :ws, 'CustomOff', 'CustomOff', 'Custom', 'CHANGELOG', 'REGEX', 1, '{}'::jsonb, 'x', false)
                """).param("id", custom).param("ws", workspaceId).update();
        jdbc.sql("insert into policy_package_check (package_id, check_id) values (:pkg, :chk)")
                .param("pkg", packageDefault).param("chk", custom).update();

        assertThat(codes(effectiveBody("PRD", null).get("checks"))).containsExactly("RollbackRequired");
    }

    // -------------------------------------------------------------- exceptions

    private void exception(String status, Instant expiresAt, UUID environmentId, UUID projectOf) {
        jdbc.sql("""
                insert into policy_exception (id, workspace_id, check_id, environment_id, project_id, justification,
                                              requested_by, approved_by, status, expires_at)
                values (:id, :ws, :chk, :env, :proj, 'because', 'alice', 'bob', :status, :expires)
                """)
                .param("id", UUID.randomUUID()).param("ws", workspaceId).param("chk", checkId("RollbackRequired"))
                .param("env", environmentId).param("proj", projectOf).param("status", status)
                .param("expires", java.time.OffsetDateTime.ofInstant(expiresAt, java.time.ZoneOffset.UTC)).update();
    }

    private UUID environmentOf(String code) {
        return jdbc.sql("select id from environment where workspace_id = :ws and code = :c")
                .param("ws", workspaceId).param("c", code).query(UUID.class).single();
    }

    @Test
    @DisplayName("only approved, unexpired exceptions for this environment and project are handed over")
    void exceptionsInForce() {
        Instant tomorrow = Instant.now().plusSeconds(86_400);
        exception("APPROVED", tomorrow, null, null);                                   // in force
        exception("APPROVED", Instant.now().minusSeconds(60), null, null);             // lapsed
        exception("REQUESTED", tomorrow, null, null);                                  // not approved
        exception("APPROVED", tomorrow, environmentOf("DEV"), null);                   // another environment
        exception("APPROVED", tomorrow, null, UUID.randomUUID());                      // another project

        JsonNode exceptions = effectiveBody("PRD", null).get("exceptions");

        assertThat(exceptions.size()).isEqualTo(1);
        assertThat(exceptions.get(0).get("check").asString()).isEqualTo("RollbackRequired");
        assertThat(exceptions.get(0).get("expiresAt").asString()).isNotBlank();
    }

    @Test
    @DisplayName("an exception scoped to this very environment and project does apply")
    void scopedExceptionApplies() {
        exception("APPROVED", Instant.now().plusSeconds(86_400), environmentOf("PRD"), UUID.fromString(projectId));

        assertThat(effectiveBody("PRD", null).get("exceptions").size()).isEqualTo(1);
        assertThat(effectiveBody("DEV", null).get("exceptions").size()).as("not in DEV").isZero();
    }

    // -------------------------------------------------------------------- ETag

    @Test
    @DisplayName("the answer carries an ETag, a repeat with it is a 304, and a real change moves it")
    void etag() {
        HttpResponse<String> first = effective("PRD", null);
        String etag = first.headers().firstValue("ETag").orElseThrow();
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(etag).startsWith("W/\"");
        assertThat(json(first).get("etag").asString()).as("the body repeats it for collectors that cache by body").isEqualTo(etag);

        HttpResponse<String> again = getWith(API + "/policies/effective?project=" + project + "&env=PRD", token,
                Map.of("If-None-Match", etag));
        assertThat(again.statusCode()).isEqualTo(304);
        assertThat(again.body()).isEmpty();

        exception("APPROVED", Instant.now().plusSeconds(86_400), null, null);
        HttpResponse<String> changed = getWith(API + "/policies/effective?project=" + project + "&env=PRD", token,
                Map.of("If-None-Match", etag));
        assertThat(changed.statusCode()).as("the answer changed, so the old ETag no longer matches").isEqualTo(200);
        assertThat(changed.headers().firstValue("ETag").orElseThrow()).isNotEqualTo(etag);
    }

    @Test
    @DisplayName("the same content always yields the same ETag")
    void etagIsStable() {
        assertThat(effective("PRD", null).headers().firstValue("ETag").orElseThrow())
                .isEqualTo(effective("PRD", null).headers().firstValue("ETag").orElseThrow());
    }

    // -------------------------------------------------------------- the edges

    @Test
    @DisplayName("a workspace with no default package gets an empty package, not an error")
    void noPackageAtAll() {
        String bare = unique("bare");
        String bareId = json(post(API + "/workspaces", "{\"slug\":\"%s\",\"name\":\"Bare\"}".formatted(bare))).get("id").asString();
        callIn(bare, "POST", API + "/environments", "{\"code\":\"PRD\",\"name\":\"PRD\",\"sortOrder\":1,\"isProduction\":true}");
        String bareToken = tokens.issue(UUID.fromString(bareId), unique("t"), List.of("ingest"), null,
                Instant.now().plusSeconds(3600), "integration-test").token();

        JsonNode body = json(call("GET", API + "/policies/effective?project=x&env=PRD", bareToken, null));

        assertThat(body.get("package").asString()).isEqualTo("none");
        assertThat(body.get("checks").size()).isZero();
    }

    @Test
    @DisplayName("an unknown environment is a 422 with LQM-422")
    void unknownEnvironment() {
        HttpResponse<String> response = effective("NOPE", null);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(json(response).get("code").asString()).isEqualTo("LQM-422");
    }

    @Test
    @DisplayName("no token is 401, and a token with neither ingest nor checks:read is 403")
    void authentication() {
        assertThat(get(API + "/policies/effective?project=x&env=PRD").statusCode()).isEqualTo(401);

        String runnerOnly = tokens.issue(workspaceId, unique("run"), List.of("runner"), null,
                Instant.now().plusSeconds(3600), "integration-test").token();
        assertThat(call("GET", API + "/policies/effective?project=x&env=PRD", runnerOnly, null).statusCode())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("a collector sees only ITS workspace's package, whatever it asks for")
    void workspaceComesFromTheToken() {
        // The seeded token belongs to the default workspace, which has 20+ checks; this
        // workspace's package has one. The answer follows the token, not the project name.
        JsonNode mine = effectiveBody("PRD", null);
        JsonNode theirs = json(call("GET", API + "/policies/effective?project=" + project + "&env=PRD",
                serviceToken("checks:read"), null));

        assertThat(codes(mine.get("checks"))).hasSize(1);
        assertThat(theirs.get("checks").size()).isGreaterThan(10);
    }

    // ----------------------------------------------------------------- library

    @Test
    @DisplayName("the library lists the whole catalogue and says which checks the engine really implements")
    void library() {
        JsonNode all = json(get(API + "/policy-checks"));
        JsonNode rollback = null;
        JsonNode notYet = null;
        for (JsonNode c : all) {
            if ("RollbackRequired".equals(c.get("code").asString())) {
                rollback = c;
            }
            if ("DriftDetected".equals(c.get("code").asString())) {
                notYet = c;
            }
        }

        assertThat(all.size()).as("51 configurable plus 4 process checks").isGreaterThanOrEqualTo(55);
        assertThat(rollback.get("implemented").asBoolean()).isTrue();
        assertThat(rollback.get("prebuilt").asBoolean()).isTrue();
        // Every configurable check has an implementation now; what the engine does not evaluate are the process checks,
        // which the system raises itself (here, the drift job).
        assertThat(notYet).as("a process check is in the catalogue").isNotNull();
        assertThat(notYet.get("implemented").asBoolean()).as("but the engine does not evaluate it: the drift job raises it").isFalse();
    }

    @Test
    @DisplayName("the library filters by category, by enabled state and by text")
    void libraryFilters() {
        assertThat(json(get(API + "/policy-checks?category=Padr%C3%B5es%20de%20changeset")).size()).isGreaterThan(5);
        assertThat(json(get(API + "/policy-checks?enabled=false")).valueStream()
                .allMatch(c -> !c.get("enabled").asBoolean())).isTrue();
        JsonNode found = json(get(API + "/policy-checks?q=rollbackrequired"));
        assertThat(codes(found)).as("the text search reaches descriptions too, so a chained check that mentions it also matches")
                .contains("RollbackRequired");
        assertThat(json(get(API + "/policy-checks?q=zzz-nothing")).size()).isZero();
    }

    @Test
    @DisplayName("the summary counts the catalogue, leaving the process checks out of 'prebuilt'")
    void librarySummary() {
        JsonNode summary = json(get(API + "/policy-checks/summary"));

        assertThat(summary.get("prebuilt").asInt()).isEqualTo(51);
        assertThat(summary.get("prebuiltCategories").asInt()).isEqualTo(7);
        assertThat(summary.get("implemented").asInt()).isGreaterThanOrEqualTo(33);
    }

    @Test
    @DisplayName("one check comes back with its message template and parameters schema")
    void libraryDetail() {
        String id = null;
        for (JsonNode c : json(get(API + "/policy-checks?q=rollbackrequired"))) {
            if ("RollbackRequired".equals(c.get("code").asString())) {
                id = c.get("id").asString();
            }
        }

        JsonNode detail = json(get(API + "/policy-checks/" + id));

        assertThat(detail.get("code").asString()).isEqualTo("RollbackRequired");
        assertThat(detail.get("messageTemplate").asString()).isNotBlank();
        assertThat(detail.get("parametersSchema").asString()).isNotBlank();
        assertThat(get(API + "/policy-checks/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }
}
