package io.github.carloslimajlle.liquimanager.access;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/**
 * Permissions enforced for real: requests that name a user (X-LQM-User) go through the same rules a
 * JWT caller would. The unnamed development caller still has full access, which is what lets the
 * test arrange its data.
 */
class AccessIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String workspace;
    private UUID workspaceId;
    private String ana;      // developer
    private String bruno;    // admin
    private String carla;    // reader
    private String prdEnvironment;
    private String devEnvironment;

    @BeforeEach
    void scenario() {
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Access\"}".formatted(workspace))).get("id").asString());
        devEnvironment = json(admin("POST", "/environments",
                "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}")).get("id").asString();
        prdEnvironment = json(admin("POST", "/environments",
                "{\"code\":\"PRD\",\"name\":\"PRD\",\"sortOrder\":2,\"isProduction\":true}")).get("id").asString();
        ana = unique("ana");
        bruno = unique("bruno");
        carla = unique("carla");
        bind(ana, "DEV");
        bind(bruno, "ADMIN");
        bind(carla, "READER");
    }

    // ----------------------------------------------------------------- helpers

    private HttpResponse<String> admin(String method, String path, String body) {
        return callIn(workspace, method, API + path, body);
    }

    private HttpResponse<String> as(String user, String method, String path, String body) {
        return callAs(user, workspace, method, API + path, body);
    }

    private String principal(String externalId) {
        HttpResponse<String> response = admin("POST", "/principals",
                "{\"type\":\"USER\",\"externalId\":\"%s\",\"displayName\":\"%s\"}".formatted(externalId, externalId));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    /** Grants a template at workspace level to a new principal with this external id. */
    private JsonNode bind(String user, String template) {
        HttpResponse<String> response = admin("POST", "/access/bindings", """
                { "principalId": "%s", "roleTemplate": "%s", "scopeType": "WORKSPACE" }
                """.formatted(principal(user), template));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response);
    }

    private HttpResponse<String> grant(String principalId, String json) {
        return admin("POST", "/access/bindings", json.replace("PRINCIPAL", principalId));
    }

    private static String in(long days) {
        return Instant.now().plus(days, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    // -------------------------------------------------------------- enforcement

    @Test
    @DisplayName("someone with no binding can read nothing and write nothing")
    void noBindingNoAccess() {
        String nobody = unique("nobody");

        assertThat(as(nobody, "GET", "/projects", null).statusCode()).isEqualTo(403);
        assertThat(as(nobody, "POST", "/projects", "{\"slug\":\"x\",\"name\":\"x\"}").statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("a reader reads but cannot write; an admin can do both")
    void readerAndAdmin() {
        assertThat(as(carla, "GET", "/projects", null).statusCode()).isEqualTo(200);
        assertThat(as(carla, "POST", "/projects", "{\"slug\":\"%s\",\"name\":\"x\"}".formatted(unique("p"))).statusCode())
                .isEqualTo(403);
        assertThat(as(bruno, "POST", "/projects", "{\"slug\":\"%s\",\"name\":\"x\"}".formatted(unique("p"))).statusCode())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("each write needs the permission of its area")
    void permissionsPerArea() {
        // ana is a developer: she may request an exception, not edit policy, not manage tokens or access
        assertThat(as(ana, "POST", "/policy-checks",
                "{\"code\":\"X1x\",\"category\":\"c\",\"kind\":\"REGEX\",\"parameters\":{\"pattern\":\"a\"}}").statusCode()).isEqualTo(403);
        assertThat(as(ana, "GET", "/service-tokens", null).statusCode()).isEqualTo(403);
        assertThat(as(ana, "POST", "/access/bindings", "{}").statusCode()).isEqualTo(403);
        assertThat(as(ana, "POST", "/policy-exceptions",
                "{\"check\":\"RollbackRequired\",\"justification\":\"%s\",\"expiresAt\":\"%s\"}"
                        .formatted("Justificativa com mais de vinte caracteres", in(3))).statusCode()).isEqualTo(201);
        // ...and she cannot approve her own (no permission) nor anyone's (not hers to approve)
        assertThat(as(ana, "POST", "/policy-exceptions/" + UUID.randomUUID() + "/approve", null).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("a binding on one project does not reach another")
    void projectScope() {
        String mine = json(admin("POST", "/projects", "{\"slug\":\"%s\",\"name\":\"mine\"}".formatted(unique("p")))).get("id").asString();
        String other = json(admin("POST", "/projects", "{\"slug\":\"%s\",\"name\":\"other\"}".formatted(unique("p")))).get("id").asString();
        String dana = unique("dana");
        HttpResponse<String> granted = grant(principal(dana), """
                { "principalId": "PRINCIPAL", "permissions": ["assignment:view", "catalog:edit"],
                  "scopeType": "PROJECT", "scopeId": "%s" }
                """.formatted(mine));
        assertThat(granted.statusCode()).as(granted.body()).isEqualTo(201);
        String changelog = "{\"name\":\"m\",\"path\":\"db/%s.xml\",\"format\":\"XML\"}";

        assertThat(as(dana, "POST", "/projects/" + mine + "/changelogs", changelog.formatted(unique("a"))).statusCode()).isEqualTo(201);
        assertThat(as(dana, "POST", "/projects/" + other + "/changelogs", changelog.formatted(unique("b"))).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("a denial beats a grant")
    void denialBeatsGrant() {
        String erin = unique("erin");
        HttpResponse<String> granted = grant(principal(erin), """
                { "principalId": "PRINCIPAL", "roleTemplate": "ADMIN", "deniedPermissions": ["assignment:view"],
                  "scopeType": "WORKSPACE" }
                """);
        assertThat(granted.statusCode()).as(granted.body()).isEqualTo(201);

        assertThat(as(erin, "GET", "/projects", null).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("an expired binding grants nothing")
    void expiredBinding() {
        String frank = unique("frank");
        grant(principal(frank), """
                { "principalId": "PRINCIPAL", "roleTemplate": "READER", "scopeType": "WORKSPACE" }
                """);
        assertThat(as(frank, "GET", "/projects", null).statusCode()).isEqualTo(200);

        jdbc.sql("update role_binding set expires_at = now() - interval '1 minute' where principal_id = "
                + "(select id from principal where external_id = :id)").param("id", frank).update();

        assertThat(as(frank, "GET", "/projects", null).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("the unnamed development caller keeps full access")
    void developmentCallerIsOpen() {
        assertThat(admin("GET", "/projects", null).statusCode()).isEqualTo(200);
        assertThat(admin("POST", "/projects", "{\"slug\":\"%s\",\"name\":\"x\"}".formatted(unique("p"))).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("/me tells a named user what they hold")
    void me() {
        JsonNode me = json(as(ana, "GET", "/me", null));

        assertThat(me.get("name").asString()).isEqualTo(ana);
        assertThat(me.get("devMode").asBoolean()).isFalse();
        assertThat(me.get("permissions").valueStream().map(JsonNode::asString).toList())
                .contains("exception:request", "deploy:dev,tst");
        assertThat(json(admin("GET", "/me", null)).get("devMode").asBoolean()).isTrue();
    }

    // --------------------------------------------------------------------- SoD

    @Test
    @DisplayName("SoD-3: a binding cannot deploy to production and approve exceptions (LQM-010)")
    void sod3() {
        String principal = principal(unique("gina"));

        HttpResponse<String> refused = grant(principal, """
                { "principalId": "PRINCIPAL", "roleTemplate": "DBA", "scopeType": "WORKSPACE" }
                """);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(json(refused).get("code").asString()).isEqualTo("LQM-010");

        HttpResponse<String> allowed = grant(principal, """
                { "principalId": "PRINCIPAL", "roleTemplate": "DBA", "deniedPermissions": ["exception:approve"],
                  "scopeType": "WORKSPACE" }
                """);
        assertThat(allowed.statusCode()).as(allowed.body()).isEqualTo(201);
    }

    @Test
    @DisplayName("SoD-3 does not apply to a non-production environment scope")
    void sod3NotInDev() {
        HttpResponse<String> response = grant(principal(unique("hugo")), """
                { "principalId": "PRINCIPAL", "roleTemplate": "DBA", "scopeType": "ENVIRONMENT", "scopeId": "%s" }
                """.formatted(devEnvironment));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    }

    @Test
    @DisplayName("SoD-4: production access needs an approver and an expiry within 90 days")
    void sod4() {
        String principal = principal(unique("iris"));
        String body = """
                { "principalId": "PRINCIPAL", "roleTemplate": "RELEASE", "scopeType": "ENVIRONMENT", "scopeId": "%s"%s }
                """;

        assertThat(grant(principal, body.formatted(prdEnvironment, "")).statusCode()).as("no approver").isEqualTo(409);
        assertThat(grant(principal, body.formatted(prdEnvironment, ",\"approver\":\"bruno\"")).statusCode()).as("no expiry").isEqualTo(409);
        assertThat(grant(principal, body.formatted(prdEnvironment, ",\"approver\":\"bruno\",\"expiresAt\":\"" + in(100) + "\"")).statusCode())
                .as("too long").isEqualTo(409);
        assertThat(grant(principal, body.formatted(prdEnvironment, ",\"approver\":\"bruno\",\"expiresAt\":\"" + in(30) + "\"")).statusCode())
                .isEqualTo(201);
    }

    @Test
    @DisplayName("break-glass lasts at most 24 hours, and needs an approver")
    void breakGlass() {
        String principal = principal(unique("jose"));
        String body = """
                { "principalId": "PRINCIPAL", "roleTemplate": "BREAKGLASS", "scopeType": "WORKSPACE"%s }
                """;

        assertThat(grant(principal, body.formatted("")).statusCode()).isEqualTo(409);
        assertThat(grant(principal, body.formatted(",\"approver\":\"bruno\",\"expiresAt\":\"" + in(2) + "\"")).statusCode()).isEqualTo(409);
        assertThat(grant(principal, body.formatted(",\"approver\":\"bruno\",\"expiresAt\":\""
                + Instant.now().plus(12, ChronoUnit.HOURS) + "\"")).statusCode()).isEqualTo(201);
    }

    @Test
    @DisplayName("a binding must say something, and say it in the permission language")
    void bindingValidation() {
        String principal = principal(unique("kim"));

        assertThat(grant(principal, "{\"principalId\":\"PRINCIPAL\",\"scopeType\":\"WORKSPACE\"}").statusCode()).isEqualTo(422);
        assertThat(grant(principal, "{\"principalId\":\"PRINCIPAL\",\"permissions\":[\"not a permission\"]}").statusCode()).isEqualTo(422);
        assertThat(grant(principal, "{\"principalId\":\"PRINCIPAL\",\"roleTemplate\":\"NOPE\"}").statusCode()).isEqualTo(422);
        assertThat(grant(principal, "{\"principalId\":\"PRINCIPAL\",\"roleTemplate\":\"READER\",\"scopeType\":\"PROJECT\"}").statusCode()).isEqualTo(422);
        assertThat(grant(UUID.randomUUID().toString(), "{\"principalId\":\"PRINCIPAL\",\"roleTemplate\":\"READER\"}").statusCode()).isEqualTo(422);
    }

    // ------------------------------------------------------------------- audit

    @Test
    @DisplayName("grants and revocations are audited and counted")
    void audited() {
        JsonNode binding = bind(unique("lia"), "READER");
        String id = binding.get("id").asString();

        assertThat(admin("DELETE", "/access/bindings/" + id, null).statusCode()).isEqualTo(204);

        long events = jdbc.sql("select count(*) from audit_event where target_id = :id and action in ('access.grant','access.revoke')")
                .param("id", id).query(Long.class).single();
        assertThat(events).isEqualTo(2);
        JsonNode summary = json(admin("GET", "/access/bindings", null)).get("summary");
        assertThat(summary.get("auditEvents").asLong()).isGreaterThan(0);
        assertThat(summary.get("members").asLong()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("the table shows what each binding really grants, and filters by scope")
    void tableAndFilter() {
        JsonNode all = json(admin("GET", "/access/bindings", null)).get("bindings");
        JsonNode developer = null;
        for (JsonNode b : all) {
            if (ana.equals(b.get("principal").get("externalId").asString())) {
                developer = b;
            }
        }
        assertThat(developer).isNotNull();
        assertThat(developer.get("roleTemplate").asString()).isEqualTo("DEV");
        assertThat(developer.get("permissions").valueStream().map(JsonNode::asString).toList()).contains("exception:request");

        assertThat(json(admin("GET", "/access/bindings?scopeType=PROJECT", null)).get("bindings")).isEmpty();
    }
}
