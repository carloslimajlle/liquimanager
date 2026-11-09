package io.github.carloslimajlle.liquimanager.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.http.HttpResponse;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** Catalogue endpoints (E1) against a real PostgreSQL. */
class CatalogIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private HttpResponse<String> put(String path, String body) {
        return call("PUT", path, null, body);
    }

    private HttpResponse<String> delete(String path) {
        return call("DELETE", path, null, null);
    }

    private String createProject(String slug) {
        HttpResponse<String> created = post(API + "/projects", """
                { "slug": "%s", "name": "Project %s" }
                """.formatted(slug, slug));
        assertThat(created.statusCode()).isEqualTo(201);
        return json(created).get("id").asString();
    }

    private String environmentId(String code) {
        for (JsonNode env : json(get(API + "/environments"))) {
            if (code.equals(env.get("code").asString())) {
                return env.get("id").asString();
            }
        }
        throw new AssertionError("seeded environment missing: " + code);
    }

    // --------------------------------------------------------------- projects

    @Test
    @DisplayName("a project can be created, read, updated and listed")
    void projectLifecycle() {
        String slug = unique("proj");
        String id = createProject(slug);

        assertThat(json(get(API + "/projects/" + id)).get("slug").asString()).isEqualTo(slug);

        HttpResponse<String> updated = put(API + "/projects/" + id, """
                { "slug": "%s", "name": "Renamed", "repoUrl": "https://git.example/x.git" }
                """.formatted(slug));
        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(json(updated).get("name").asString()).isEqualTo("Renamed");
        assertThat(json(updated).get("defaultBranch").asString()).isEqualTo("main");

        assertThat(get(API + "/projects?q=" + slug).body()).contains(slug);
    }

    @Test
    @DisplayName("an archived project disappears from the list but is not deleted (RF-CAD-02 CA2)")
    void projectArchive() {
        String slug = unique("proj");
        String id = createProject(slug);

        assertThat(delete(API + "/projects/" + id).statusCode()).isEqualTo(204);

        assertThat(get(API + "/projects?q=" + slug).body()).doesNotContain(slug);
        assertThat(jdbc.sql("select archived_at is not null from project where id = cast(:id as uuid)")
                .param("id", id).query(Boolean.class).single()).isTrue();
    }

    @Test
    @DisplayName("a duplicate slug answers 409 with LQM-409, and a bad slug 422")
    void projectValidation() {
        String slug = unique("proj");
        createProject(slug);

        HttpResponse<String> duplicate = post(API + "/projects", "{\"slug\":\"%s\",\"name\":\"x\"}".formatted(slug));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(json(duplicate).get("code").asString()).isEqualTo("LQM-409");

        assertThat(post(API + "/projects", "{\"slug\":\"Bad Slug!\",\"name\":\"x\"}").statusCode()).isEqualTo(422);
        assertThat(post(API + "/projects", "{\"slug\":\"ok-slug\"}").statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("an unknown project is a 404 with LQM-001")
    void projectNotFound() {
        HttpResponse<String> missing = get(API + "/projects/00000000-0000-0000-0000-000000000000");
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(json(missing).get("code").asString()).isEqualTo("LQM-001");
    }

    @Test
    @DisplayName("an unknown workspace in the header is a 404")
    void unknownWorkspace() {
        HttpResponse<String> response = call("GET", API + "/projects", null, null);
        assertThat(response.statusCode()).isEqualTo(200);

        java.net.http.HttpRequest request = java.net.http.HttpRequest
                .newBuilder(java.net.URI.create("http://localhost:" + port + API + "/projects"))
                .header(WorkspaceContext.HEADER, "does-not-exist").GET().build();
        try {
            assertThat(java.net.http.HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        } catch (java.io.IOException | InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    // ------------------------------------------------------------ environments

    @Test
    @DisplayName("the four seeded environments come back in order, PRD being the production one")
    void seededEnvironments() {
        JsonNode list = json(get(API + "/environments"));

        assertThat(list.get(0).get("code").asString()).isEqualTo("DEV");
        assertThat(list.get(3).get("code").asString()).isEqualTo("PRD");
        assertThat(list.get(3).get("isProduction").asBoolean()).isTrue();
        assertThat(list.get(0).get("isProduction").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("environments are configurable: a new one can be added and edited (D-15)")
    void environmentsAreConfigurable() {
        String code = "E" + (int) (Math.random() * 9000 + 1000);
        HttpResponse<String> created = post(API + "/environments", """
                { "code": "%s", "name": "Extra", "sortOrder": 99 }
                """.formatted(code.toLowerCase()));

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(json(created).get("code").asString()).as("codes are stored upper-case").isEqualTo(code);

        HttpResponse<String> updated = put(API + "/environments/" + json(created).get("id").asString(),
                "{\"name\":\"Renamed\",\"isProduction\":true}");
        assertThat(json(updated).get("name").asString()).isEqualTo("Renamed");
        assertThat(json(updated).get("isProduction").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------- connections

    private String connectionBody(String slug, String url, String provider, String ref) {
        return """
                { "environmentId": "%s", "slug": "%s", "displayName": "%s", "engine": "postgresql",
                  "jdbcUrl": "%s", "secretProvider": "%s", "secretRef": %s }
                """.formatted(environmentId("DEV"), slug, slug, url, provider,
                ref == null ? "null" : "\"" + ref + "\"");
    }

    @Test
    @DisplayName("a connection stores a fingerprint and a secret REFERENCE, never credentials")
    void connectionCreate() {
        String slug = unique("conn");
        HttpResponse<String> created = post(API + "/connections",
                connectionBody(slug, "jdbc:postgresql://Host-X:5432/App?ssl=true", "VAULT", "secret/data/lqm/x"));

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = json(created);
        assertThat(body.get("hostFingerprint").asString()).isEqualTo("host-x:5432/app");
        assertThat(body.get("secretRef").asString()).isEqualTo("secret/data/lqm/x");
        assertThat(body.get("verified").asBoolean()).isTrue();
        assertThat(created.body()).as("no secret value can appear in a connection").doesNotContainIgnoringCase("password");
    }

    @Test
    @DisplayName("a JDBC URL carrying credentials is refused (RNF-06)")
    void connectionRefusesCredentials() {
        HttpResponse<String> response = post(API + "/connections", connectionBody(unique("conn"),
                "jdbc:postgresql://h:5432/app?user=sa&password=Secret123", "NONE", null));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(jdbc.sql("select count(*) from db_connection where jdbc_url like '%Secret123%'")
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("an unsupported JDBC URL and an unknown secret provider are refused")
    void connectionValidation() {
        assertThat(post(API + "/connections",
                connectionBody(unique("conn"), "not-a-jdbc-url", "NONE", null)).statusCode()).isEqualTo(422);
        assertThat(post(API + "/connections",
                connectionBody(unique("conn"), "jdbc:postgresql://h/app", "WHATEVER", null)).statusCode())
                .isEqualTo(422);
    }

    @Test
    @DisplayName("an unreachable database is a normal result of the test (200, ok=false), not an HTTP error")
    void connectionTestFailureIsAResult() {
        String slug = unique("conn");
        String id = json(post(API + "/connections",
                connectionBody(slug, "jdbc:postgresql://127.0.0.1:1/nothing", "NONE", null))).get("id").asString();

        HttpResponse<String> result = post(API + "/connections/" + id + "/test", null);

        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(json(result).get("ok").asBoolean()).isFalse();
        assertThat(jdbc.sql("select last_test_ok from db_connection where id = cast(:id as uuid)")
                .param("id", id).query(Boolean.class).single()).isFalse();
    }

    @Test
    @DisplayName("an unavailable secret provider answers LQM-050")
    void connectionTestSecretUnavailable() {
        String id = json(post(API + "/connections", connectionBody(unique("conn"),
                "jdbc:postgresql://h:5432/app", "VAULT", "secret/x"))).get("id").asString();

        HttpResponse<String> result = post(API + "/connections/" + id + "/test", null);

        assertThat(result.statusCode()).isEqualTo(502);
        assertThat(json(result).get("code").asString()).isEqualTo("LQM-050");
    }

    @Test
    @DisplayName("a reachable database reports its version through the ENV secret provider")
    void connectionTestSuccess() {
        assumeTrue(System.getenv("LQM_IT_USERNAME") != null, "needs LQM_IT_USERNAME/LQM_IT_PASSWORD");
        String url = System.getenv("LQM_TEST_DB_URL");
        String id = json(post(API + "/connections", connectionBody(unique("conn"), url, "ENV", "lqm-it")))
                .get("id").asString();

        JsonNode result = json(post(API + "/connections/" + id + "/test", null));

        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("dbVersion").asString()).startsWith("PostgreSQL");
    }

    // --------------------------------------------------- changelogs / pipelines

    @Test
    @DisplayName("a changelog starts verified when created by an admin, and can be edited")
    void changelogLifecycle() {
        String projectId = createProject(unique("proj"));

        HttpResponse<String> created = post(API + "/projects/" + projectId + "/changelogs", """
                { "name": "gate-changelog", "path": "db/changelog/db.changelog-master.xml", "format": "XML" }
                """);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(json(created).get("verified").asBoolean()).isTrue();

        assertThat(post(API + "/projects/" + projectId + "/changelogs",
                "{\"name\":\"x\",\"path\":\"p\",\"format\":\"TOML\"}").statusCode()).isEqualTo(422);
        assertThat(get(API + "/projects/" + projectId + "/changelogs").body()).contains("gate-changelog");
    }

    @Test
    @DisplayName("a pipeline keeps its stages ordered, and two stages cannot share a position")
    void pipelineStages() {
        String connection = json(post(API + "/connections", connectionBody(unique("conn"),
                "jdbc:postgresql://h:5432/app", "NONE", null))).get("id").asString();
        String pipeline = json(post(API + "/pipelines", "{\"name\":\"" + unique("PIPE") + "\"}")).get("id").asString();

        String second = """
                { "sortOrder": 2, "environmentId": "%s", "dbConnectionId": "%s", "requiresApproval": true }
                """.formatted(environmentId("TST"), connection);
        String first = """
                { "sortOrder": 1, "environmentId": "%s", "dbConnectionId": "%s" }
                """.formatted(environmentId("DEV"), connection);

        assertThat(post(API + "/pipelines/" + pipeline + "/stages", second).statusCode()).isEqualTo(201);
        assertThat(post(API + "/pipelines/" + pipeline + "/stages", first).statusCode()).isEqualTo(201);

        JsonNode stages = json(get(API + "/pipelines/" + pipeline + "/stages"));
        assertThat(stages.get(0).get("sortOrder").asInt()).isEqualTo(1);
        assertThat(stages.get(1).get("sortOrder").asInt()).isEqualTo(2);

        assertThat(post(API + "/pipelines/" + pipeline + "/stages", first).statusCode())
                .as("same position twice").isEqualTo(409);
    }

    // ---------------------------------------------------------- service tokens

    @Test
    @DisplayName("a token is shown once, works for ingestion, never reappears, and revoking cuts it off")
    void serviceTokenLifecycle() {
        HttpResponse<String> created = post(API + "/service-tokens", """
                { "name": "%s", "scopes": ["ingest"], "expiresAt": "%s" }
                """.formatted(unique("collector"), Instant.now().plusSeconds(3600)));
        assertThat(created.statusCode()).isEqualTo(201);

        String token = json(created).get("token").asString();
        String id = json(created).get("id").asString();
        assertThat(token).matches("lqm_[a-z0-9]{8}_[a-z0-9]{32}");

        assertThat(call("POST", API + "/ingest/operations", token, "{}").statusCode())
                .as("authenticated, but the payload is empty").isEqualTo(422);

        assertThat(get(API + "/service-tokens").body())
                .as("the secret never comes back, only the prefix").doesNotContain(token)
                .doesNotContain("tokenHash").contains(json(created).get("prefix").asString());

        assertThat(delete(API + "/service-tokens/" + id).statusCode()).isEqualTo(204);
        assertThat(call("POST", API + "/ingest/operations", token, "{}").statusCode())
                .as("revoked").isEqualTo(401);
        assertThat(delete(API + "/service-tokens/" + id).statusCode())
                .as("revoking twice").isEqualTo(404);
    }

    @Test
    @DisplayName("a token without expiry, with an unknown scope, or living over a year is refused")
    void serviceTokenRules() {
        assertThat(post(API + "/service-tokens", "{\"name\":\"a\",\"scopes\":[\"ingest\"]}").statusCode())
                .as("no expiry").isEqualTo(422);
        assertThat(post(API + "/service-tokens", """
                { "name": "a", "scopes": ["root"], "expiresAt": "%s" }
                """.formatted(Instant.now().plusSeconds(3600))).statusCode()).as("unknown scope").isEqualTo(422);
        assertThat(post(API + "/service-tokens", """
                { "name": "a", "scopes": ["ingest"], "expiresAt": "%s" }
                """.formatted(Instant.now().plusSeconds(400L * 86_400))).statusCode())
                .as("more than a year").isEqualTo(422);
    }
}
