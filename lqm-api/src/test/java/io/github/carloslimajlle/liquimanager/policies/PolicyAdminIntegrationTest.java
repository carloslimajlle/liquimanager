package io.github.carloslimajlle.liquimanager.policies;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import io.github.carloslimajlle.liquimanager.audit.AuditService;
import tools.jackson.databind.JsonNode;

/**
 * Writing the policy library (E9): custom checks, copies of builtins, chains, packages and
 * the dry-run. Everything happens in an isolated workspace, so the seeded catalogue of the
 * shared one is never touched.
 */
class PolicyAdminIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    @Autowired
    AuditService audit;

    private String workspace;
    private UUID workspaceId;
    private String token;
    private String project;

    @BeforeEach
    void isolatedWorkspace() {
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Admin\"}".formatted(workspace))).get("id").asString());
        callIn(workspace, "POST", API + "/environments",
                "{\"code\":\"PRD\",\"name\":\"PRD\",\"sortOrder\":1,\"isProduction\":true}");
        token = tokens.issue(workspaceId, unique("adm"), List.of("checks:read"), null,
                Instant.now().plusSeconds(3600), "integration-test").token();
        project = unique("proj");
        callIn(workspace, "POST", API + "/projects", "{\"slug\":\"%s\",\"name\":\"P\"}".formatted(project));
    }

    // ----------------------------------------------------------------- helpers

    private HttpResponse<String> in(String method, String path, String body) {
        return callIn(workspace, method, API + path, body);
    }

    private JsonNode created(String path, String body) {
        HttpResponse<String> response = in("POST", path, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response);
    }

    private JsonNode regexCheck(String code, String pattern, int severity) {
        return created("/policy-checks", """
                { "code": "%s", "category": "Padroes", "kind": "REGEX", "defaultSeverity": %d,
                  "parameters": { "pattern": "%s", "appliesTo": "SQL" },
                  "messageTemplate": "Proibido em <CHANGESET_ID>" }
                """.formatted(code, severity, pattern));
    }

    private JsonNode packageNamed(String name, String... codes) {
        StringBuilder checks = new StringBuilder();
        for (String code : codes) {
            checks.append(checks.isEmpty() ? "" : ",").append("{\"code\":\"").append(code).append("\"}");
        }
        return created("/policy-packages", "{\"name\":\"%s\",\"checks\":[%s]}".formatted(name, checks));
    }

    private JsonNode effective() {
        HttpResponse<String> response = call("GET",
                API + "/policies/effective?project=" + project + "&env=PRD", token, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private static JsonNode entry(JsonNode effective, String code) {
        for (JsonNode check : effective.get("checks")) {
            if (code.equals(check.get("code").asString())) {
                return check;
            }
        }
        throw new AssertionError("not in the package: " + code);
    }

    private static final String CHANGELOG = """
            <?xml version="1.0" encoding="UTF-8"?>
            <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
              <changeSet id="SUP-1-001" author="p.ricardo">
                <sql>select * from t with (nolock)</sql>
              </changeSet>
              <changeSet id="SUP-1-002" author="p.ricardo">
                <sql>select 1</sql>
              </changeSet>
            </databaseChangeLog>
            """;

    // ------------------------------------------------------------ custom checks

    @Test
    @DisplayName("a custom check is created, listed as implemented, and shows up in the effective package")
    void createAndUse() {
        JsonNode check = regexCheck("NoNolock", "nolock", 3);
        assertThat(check.get("kind").asString()).isEqualTo("REGEX");
        packageNamed("default", "NoNolock");

        JsonNode entry = entry(effective(), "NoNolock");

        assertThat(entry.get("kind").asString()).isEqualTo("REGEX");
        assertThat(entry.get("severity").asInt()).isEqualTo(3);
        assertThat(entry.get("parameters").get("pattern").asString()).isEqualTo("nolock");
        assertThat(entry.get("message").asString()).isEqualTo("Proibido em <CHANGESET_ID>");
        JsonNode listed = json(in("GET", "/policy-checks?q=NoNolock", null)).get(0);
        assertThat(listed.get("implemented").asBoolean()).isTrue();
        assertThat(listed.get("prebuilt").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("a SPEL check is listed as implemented, and one that does not parse is refused when saved")
    void spelCheck() {
        assertThat(in("POST", "/policy-checks", """
                { "code": "BadSpel", "category": "X", "kind": "SPEL", "parameters": { "expression": "changeSet.comments ==" } }
                """).statusCode()).isEqualTo(422);
        assertThat(in("POST", "/policy-checks", """
                { "code": "NoCommentInPrd", "category": "X", "kind": "SPEL", "defaultSeverity": 3,
                  "parameters": { "expression": "changeSet.comments == null && env == 'PRD'" } }
                """).statusCode()).isEqualTo(201);

        assertThat(json(in("GET", "/policy-checks?q=NoCommentInPrd", null)).get(0).get("implemented").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("what is invalid is refused when saved, not when a deploy first meets it")
    void validation() {
        assertThat(in("POST", "/policy-checks", """
                { "code": "BadRegex", "category": "X", "kind": "REGEX", "parameters": { "pattern": "([oops" } }
                """).statusCode()).isEqualTo(422);
        assertThat(in("POST", "/policy-checks", """
                { "code": "bad code!", "category": "X", "kind": "REGEX", "parameters": { "pattern": "a" } }
                """).statusCode()).isEqualTo(422);
        assertThat(in("POST", "/policy-checks", """
                { "code": "NoKind", "category": "X", "kind": "MAGIC", "parameters": {} }
                """).statusCode()).isEqualTo(422);
        assertThat(in("POST", "/policy-checks", """
                { "code": "BadOperator", "category": "X", "kind": "SQL_PATTERN", "parameters": { "operator": "NEAR" } }
                """).statusCode()).isEqualTo(422);
        assertThat(in("POST", "/policy-checks", """
                { "code": "EmptyChain", "category": "X", "kind": "CHAINED", "members": [] }
                """).statusCode()).isEqualTo(422);
        assertThat(in("POST", "/policy-checks", """
                { "code": "Sev", "category": "X", "kind": "REGEX", "defaultSeverity": 9, "parameters": { "pattern": "a" } }
                """).statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("a code is unique, and cannot reuse a builtin's")
    void codesAreUnique() {
        regexCheck("Mine", "a", 2);

        assertThat(in("POST", "/policy-checks", """
                { "code": "Mine", "category": "X", "kind": "REGEX", "parameters": { "pattern": "b" } }
                """).statusCode()).isEqualTo(409);
        assertThat(in("POST", "/policy-checks", """
                { "code": "RollbackRequired", "category": "X", "kind": "REGEX", "parameters": { "pattern": "b" } }
                """).statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("editing changes what the effective package says, and the ETag with it")
    void editChangesTheAnswer() {
        JsonNode check = regexCheck("EditMe", "old", 2);
        packageNamed("default", "EditMe");
        String etagBefore = effective().get("etag").asString();

        HttpResponse<String> updated = in("PUT", "/policy-checks/" + check.get("id").asString(), """
                { "defaultSeverity": 4, "parameters": { "pattern": "new", "appliesTo": "SQL" }, "shortName": "Edited" }
                """);

        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        JsonNode entry = entry(effective(), "EditMe");
        assertThat(entry.get("severity").asInt()).isEqualTo(4);
        assertThat(entry.get("parameters").get("pattern").asString()).isEqualTo("new");
        assertThat(effective().get("etag").asString()).isNotEqualTo(etagBefore);
    }

    @Test
    @DisplayName("a disabled check is not in the effective package")
    void disabledCheckDisappears() {
        JsonNode check = regexCheck("SwitchOff", "x", 2);
        packageNamed("default", "SwitchOff");
        assertThat(effective().get("checks")).hasSize(1);

        in("PUT", "/policy-checks/" + check.get("id").asString(), "{\"enabled\": false}");

        assertThat(effective().get("checks")).isEmpty();
    }

    @Test
    @DisplayName("a builtin cannot be edited or deleted")
    void builtinsAreReadOnly() {
        String builtin = builtinId("RollbackRequired");

        assertThat(in("PUT", "/policy-checks/" + builtin, "{\"defaultSeverity\": 0}").statusCode()).isEqualTo(409);
        assertThat(in("DELETE", "/policy-checks/" + builtin, null).statusCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("a check in a package cannot be deleted; once removed from it, it can")
    void deleteGuardsUse() {
        JsonNode check = regexCheck("InUse", "x", 2);
        JsonNode pkg = packageNamed("default", "InUse");

        HttpResponse<String> refused = in("DELETE", "/policy-checks/" + check.get("id").asString(), null);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("default");

        in("PUT", "/policy-packages/" + pkg.get("id").asString(), "{\"checks\": []}");
        assertThat(in("DELETE", "/policy-checks/" + check.get("id").asString(), null).statusCode()).isEqualTo(204);
    }

    // ------------------------------------------------------------------- copies

    @Test
    @DisplayName("a copy of a builtin runs the parent's logic under its own name and severity")
    void copyOfBuiltin() {
        String builtin = builtinId("DropColumnWarn");

        JsonNode copy = created("/policy-checks/" + builtin + "/copy", "{\"code\":\"DropColumnForbidden\"}");
        in("PUT", "/policy-checks/" + copy.get("id").asString(), "{\"defaultSeverity\": 4}");
        packageNamed("default", "DropColumnForbidden");

        JsonNode entry = entry(effective(), "DropColumnForbidden");
        assertThat(entry.get("kind").asString()).isEqualTo("BUILTIN");
        assertThat(entry.get("implementation").asString()).isEqualTo("DropColumnWarn");
        assertThat(entry.get("severity").asInt()).isEqualTo(4);
        assertThat(json(in("GET", "/policy-checks?q=DropColumnForbidden", null)).get(0).get("implemented").asBoolean())
                .as("implemented because its parent is").isTrue();
    }

    @Test
    @DisplayName("copying without a code picks a free one")
    void copyPicksACode() {
        String builtin = builtinId("DropColumnWarn");

        JsonNode first = created("/policy-checks/" + builtin + "/copy", "{}");
        JsonNode second = created("/policy-checks/" + builtin + "/copy", "{}");

        assertThat(first.get("code").asString()).isEqualTo("DropColumnWarnCustom");
        assertThat(second.get("code").asString()).isEqualTo("DropColumnWarnCustom2");
    }

    @Test
    @DisplayName("a copy of a copy still points at the root builtin")
    void copyOfCopy() {
        String builtin = builtinId("DropColumnWarn");
        JsonNode copy = created("/policy-checks/" + builtin + "/copy", "{\"code\":\"First\"}");
        JsonNode again = created("/policy-checks/" + copy.get("id").asString() + "/copy", "{\"code\":\"Second\"}");
        packageNamed("default", "Second");

        assertThat(entry(effective(), "Second").get("implementation").asString()).isEqualTo("DropColumnWarn");
        assertThat(again.get("parentId").asString()).isEqualTo(builtin);
    }

    // ------------------------------------------------------------------- chains

    @Test
    @DisplayName("a chain lists its members in the effective package")
    void chain() {
        regexCheck("MemberA", "a", 2);
        created("/policy-checks", """
                { "code": "Standards", "category": "X", "kind": "CHAINED", "members": ["MemberA", "RollbackRequired"] }
                """);
        packageNamed("default", "Standards");

        JsonNode entry = entry(effective(), "Standards");

        assertThat(entry.get("kind").asString()).isEqualTo("CHAINED");
        assertThat(entry.get("members").valueStream().map(m -> m.get("code").asString()).toList())
                .containsExactlyInAnyOrder("MemberA", "RollbackRequired");
    }

    @Test
    @DisplayName("a chain member cannot itself be a chain, nor an unknown check")
    void chainMembersAreValidated() {
        created("/policy-checks", """
                { "code": "Inner", "category": "X", "kind": "CHAINED", "members": ["RollbackRequired"] }
                """);

        assertThat(in("POST", "/policy-checks", """
                { "code": "Outer", "category": "X", "kind": "CHAINED", "members": ["Inner"] }
                """).statusCode()).isEqualTo(422);
        assertThat(in("POST", "/policy-checks", """
                { "code": "Ghost", "category": "X", "kind": "CHAINED", "members": ["NoSuchCheck"] }
                """).statusCode()).isEqualTo(422);
    }

    // ------------------------------------------------------------------ dry-run

    @Test
    @DisplayName("the dry-run says what the check finds in a pasted changelog")
    void dryRun() {
        JsonNode check = regexCheck("NoNolockDry", "nolock", 3);

        HttpResponse<String> response = in("POST", "/policy-checks/" + check.get("id").asString() + "/test", """
                { "changelogContent": %s, "format": "XML", "dialect": "mssql" }
                """.formatted(JSON.writeValueAsString(CHANGELOG)));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode result = json(response);
        assertThat(result.get("changesets").asInt()).isEqualTo(2);
        assertThat(result.get("findings")).hasSize(1);
        assertThat(result.get("findings").get(0).get("message").asString()).isEqualTo("Proibido em SUP-1-001");
        assertThat(result.get("findings").get(0).get("severity").asInt()).isEqualTo(3);
    }

    @Test
    @DisplayName("the dry-run can try unsaved parameters, and runs a builtin the same way the collectors do")
    void dryRunWithOverridesAndBuiltin() {
        JsonNode check = regexCheck("NoSelect", "nothing-matches-this", 2);
        HttpResponse<String> response = in("POST", "/policy-checks/" + check.get("id").asString() + "/test", """
                { "changelogContent": %s, "format": "XML", "parameters": { "pattern": "select 1" } }
                """.formatted(JSON.writeValueAsString(CHANGELOG)));
        assertThat(json(response).get("findings")).hasSize(1);

        String builtin = builtinId("RollbackRequired");
        JsonNode viaBuiltin = json(in("POST", "/policy-checks/" + builtin + "/test", """
                { "changelogContent": %s, "format": "XML" }
                """.formatted(JSON.writeValueAsString(CHANGELOG))));
        assertThat(viaBuiltin.get("findings").valueStream().map(f -> f.get("check").asString()).distinct().toList())
                .containsExactly("RollbackRequired");
    }

    @Test
    @DisplayName("a changelog that does not parse is the caller's error, not the server's")
    void dryRunBadInput() {
        JsonNode check = regexCheck("Anything", "a", 2);
        String path = "/policy-checks/" + check.get("id").asString() + "/test";

        assertThat(in("POST", path, "{\"changelogContent\": \"<not xml\", \"format\": \"XML\"}").statusCode())
                .isEqualTo(422);
        assertThat(in("POST", path, "{\"changelogContent\": \"\", \"format\": \"XML\"}").statusCode()).isEqualTo(422);
        assertThat(in("POST", path, "{\"changelogContent\": \"x\", \"format\": \"DOCX\"}").statusCode()).isEqualTo(422);
    }

    // ----------------------------------------------------------------- packages

    @Test
    @DisplayName("a package is created with severity overrides and the effective package honours them")
    void packageOverrides() {
        regexCheck("Overridden", "x", 2);
        created("/policy-packages", """
                { "name": "default", "blockThresholdProd": 2,
                  "checks": [ { "code": "Overridden", "severityOverride": 4, "parametersOverride": { "pattern": "y" } } ] }
                """);

        JsonNode effective = effective();

        assertThat(effective.get("blockThreshold").asInt()).isEqualTo(2);
        assertThat(entry(effective, "Overridden").get("severity").asInt()).isEqualTo(4);
        assertThat(entry(effective, "Overridden").get("parameters").get("pattern").asString()).isEqualTo("y");
    }

    @Test
    @DisplayName("a package with an unknown check, or a bad threshold, is refused")
    void packageValidation() {
        assertThat(in("POST", "/policy-packages", "{\"name\":\"p\",\"checks\":[{\"code\":\"Nope\"}]}").statusCode())
                .isEqualTo(422);
        assertThat(in("POST", "/policy-packages", "{\"name\":\"p\",\"blockThresholdProd\":9}").statusCode())
                .isEqualTo(422);
        assertThat(in("POST", "/policy-packages", "{\"name\":\" \"}").statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("the default package can be edited but neither renamed nor deleted")
    void defaultPackageIsProtected() {
        JsonNode pkg = packageNamed("default");

        assertThat(in("DELETE", "/policy-packages/" + pkg.get("id").asString(), null).statusCode()).isEqualTo(409);
        assertThat(in("PUT", "/policy-packages/" + pkg.get("id").asString(), "{\"name\":\"other\"}").statusCode())
                .isEqualTo(409);
        assertThat(in("PUT", "/policy-packages/" + pkg.get("id").asString(), "{\"description\":\"ok\"}").statusCode())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("an assigned package cannot be deleted until it is unassigned")
    void assignment() {
        packageNamed("default");
        JsonNode pkg = packageNamed("strict");
        String changelogId = json(in("POST", "/projects/" + projectId() + "/changelogs",
                "{\"name\":\"master\",\"path\":\"db/master.xml\",\"format\":\"XML\"}")).get("id").asString();

        assertThat(in("POST", "/policy-packages/" + pkg.get("id").asString() + "/assign",
                "{\"changelogId\":\"%s\"}".formatted(changelogId)).statusCode()).isEqualTo(200);
        assertThat(in("DELETE", "/policy-packages/" + pkg.get("id").asString(), null).statusCode()).isEqualTo(409);

        assertThat(in("POST", "/policy-packages/" + pkg.get("id").asString() + "/unassign",
                "{\"changelogId\":\"%s\"}".formatted(changelogId)).statusCode()).isEqualTo(200);
        assertThat(in("DELETE", "/policy-packages/" + pkg.get("id").asString(), null).statusCode()).isEqualTo(204);
    }

    @Test
    @DisplayName("assign needs exactly one target")
    void assignNeedsOneTarget() {
        JsonNode pkg = packageNamed("p");

        assertThat(in("POST", "/policy-packages/" + pkg.get("id").asString() + "/assign", "{}").statusCode())
                .isEqualTo(422);
        assertThat(in("POST", "/policy-packages/" + pkg.get("id").asString() + "/assign",
                "{\"changelogId\":\"%s\",\"stageId\":\"%s\"}".formatted(UUID.randomUUID(), UUID.randomUUID()))
                .statusCode()).isEqualTo(422);
    }

    // -------------------------------------------------------------------- audit

    @Test
    @DisplayName("every change is audited with before and after, and the chain stays intact")
    void audited() {
        JsonNode check = regexCheck("Audited", "a", 2);
        in("PUT", "/policy-checks/" + check.get("id").asString(), "{\"defaultSeverity\": 3}");
        in("DELETE", "/policy-checks/" + check.get("id").asString(), null);

        List<String> actions = jdbc.sql("""
                select action from audit_event where workspace_id = :ws and target_id = :id order by seq
                """).param("ws", workspaceId).param("id", check.get("id").asString()).query(String.class).list();
        assertThat(actions).containsExactly("policy.create", "policy.update", "policy.delete");

        String update = jdbc.sql("select cast(before as text) || ' -> ' || cast(after as text) from audit_event "
                + "where action = 'policy.update' and target_id = :id").param("id", check.get("id").asString())
                .query(String.class).single();
        assertThat(update).contains("\"defaultSeverity\": 2").contains("\"defaultSeverity\": 3");
        AuditService.Verification chain = audit.verify();
        assertThat(chain.intact()).as("chain broken at seq %s", chain.brokenAt()).isTrue();
    }

    /** The builtin with exactly this code: a text search also matches descriptions that mention it. */
    private String builtinId(String code) {
        for (JsonNode check : json(in("GET", "/policy-checks?q=" + code, null))) {
            if (code.equals(check.get("code").asString())) {
                return check.get("id").asString();
            }
        }
        throw new AssertionError("builtin not found: " + code);
    }

    private String projectId() {
        return jdbc.sql("select id from project where slug = :s and workspace_id = :ws")
                .param("s", project).param("ws", workspaceId).query(UUID.class).single().toString();
    }
}
