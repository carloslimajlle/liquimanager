package io.github.carloslimajlle.liquimanager.audit;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** The secrets panel and the audit trail, in an isolated workspace. */
class SecretsAndAuditIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String workspace;
    private UUID workspaceId;
    private String envId;

    @BeforeEach
    void scenario() {
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Secrets\"}".formatted(workspace))).get("id").asString());
        envId = json(in("POST", "/environments", "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}"))
                .get("id").asString();
    }

    private HttpResponse<String> in(String method, String path, String body) {
        return callIn(workspace, method, API + path, body);
    }

    private String connection(String slug, String provider) {
        HttpResponse<String> response = in("POST", "/connections", """
                { "environmentId": "%s", "slug": "%s", "displayName": "%s", "engine": "postgresql",
                  "jdbcUrl": "jdbc:postgresql://%s:5432/app", "secretProvider": %s, "secretRef": "ref/%s" }
                """.formatted(envId, slug, slug, slug, provider == null ? "null" : "\"" + provider + "\"", slug));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response).get("id").asString();
    }

    // ----------------------------------------------------------------- secrets

    @Test
    @DisplayName("the panel lists connections with a provider and never the secret or its reference")
    void statusListsProviders() {
        String vault = unique("vault");
        connection(vault, "VAULT");
        connection(unique("none"), null);

        HttpResponse<String> response = in("GET", "/secrets/status", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode connections = json(response).get("connections");
        assertThat(connections).hasSize(1);
        assertThat(connections.get(0).get("connection").asString()).isEqualTo(vault);
        assertThat(connections.get(0).get("provider").asString()).isEqualTo("VAULT");
        assertThat(connections.get(0).get("environment").asString()).isEqualTo("DEV");
        assertThat(response.body()).doesNotContain("ref/").doesNotContain("password");
    }

    @Test
    @DisplayName("never rotated is the worst case; announcing a rotation resets the age")
    void rotationAge() {
        String slug = unique("db");
        connection(slug, "VAULT");
        JsonNode before = json(in("GET", "/secrets/status", null)).get("connections").get(0);
        assertThat(before.get("level").asString()).isEqualTo("danger");
        assertThat(before.get("rotatedAt")).isNull();

        assertThat(in("POST", "/secrets/" + slug + "/rotated", null).statusCode()).isEqualTo(200);

        JsonNode after = json(in("GET", "/secrets/status", null)).get("connections").get(0);
        assertThat(after.get("level").asString()).isEqualTo("ok");
        assertThat(after.get("ageDays").asInt()).isZero();
    }

    @Test
    @DisplayName("ages past the thresholds read as warning and danger")
    void thresholds() {
        String a = unique("a");
        String b = unique("b");
        String c = unique("c");
        connection(a, "VAULT");
        connection(b, "VAULT");
        connection(c, "VAULT");
        jdbc.sql("update db_connection set secret_rotated_at = now() - interval '20 days' where slug = :s").param("s", a).update();
        jdbc.sql("update db_connection set secret_rotated_at = now() - interval '40 days' where slug = :s").param("s", b).update();
        jdbc.sql("update db_connection set secret_rotated_at = now() - interval '2 days' where slug = :s").param("s", c).update();

        JsonNode connections = json(in("GET", "/secrets/status", null)).get("connections");

        java.util.Map<String, String> levels = new java.util.HashMap<>();
        connections.forEach(n -> levels.put(n.get("connection").asString(), n.get("level").asString()));
        assertThat(levels).containsEntry(a, "warning").containsEntry(b, "danger").containsEntry(c, "ok");
    }

    @Test
    @DisplayName("a rotation of an unknown connection is a 404, and a real one is audited")
    void rotatedAudited() {
        assertThat(in("POST", "/secrets/nope/rotated", null).statusCode()).isEqualTo(404);
        String slug = unique("db");
        connection(slug, "VAULT");
        in("POST", "/secrets/" + slug + "/rotated", null);

        long events = jdbc.sql("select count(*) from audit_event where action = 'secret.rotated' and target_id = :id")
                .param("id", slug).query(Long.class).single();
        assertThat(events).isEqualTo(1);
    }

    @Test
    @DisplayName("secrets in changelogs counts the distinct changesets flagged by SecretInChangelog")
    void secretsInChangelogs() {
        String token = tokens.issue(workspaceId, unique("t"), List.of("ingest"), null,
                Instant.now().plusSeconds(3600), "integration-test").token();
        String project = unique("proj");
        String payload = payloadAt("DEV", UUID.randomUUID(), project, unique("c"), uniqueUrl(), "update", "SUCCESS",
                Instant.now().minusSeconds(30), changeset("SUP-LEAK", "EXECUTED"))
                .replace("\"error\":", "\"policy\": { \"gateResult\": \"BLOCKED\", \"findings\": [ { \"check\": \"SecretInChangelog\", "
                        + "\"severity\": 4, \"result\": \"BLOCKED\", \"changeset\": \"SUP-LEAK\", \"message\": \"segredo\" } ] }, \"error\":");
        assertThat(call("POST", API + "/ingest/operations", token, payload).statusCode()).isIn(200, 202);

        assertThat(json(in("GET", "/secrets/status", null)).get("secretsInChangelogs").asLong()).isEqualTo(1);
    }

    // ------------------------------------------------------------------- audit


    @Test
    @DisplayName("the trail lists newest first and filters by actor and action")
    void listAndFilter() {
        String slug = unique("db");
        connection(slug, "VAULT");
        in("POST", "/secrets/" + slug + "/rotated", null);

        JsonNode all = json(in("GET", "/audit", null));
        assertThat(all.get("totalElements").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(all.get("content").get(0).get("action").asString()).isEqualTo("secret.rotated");
        assertThat(all.get("content").get(0).get("after").get("connection").asString()).isEqualTo(slug);

        assertThat(json(in("GET", "/audit?action=secret.*", null)).get("content")).isNotEmpty();
        assertThat(json(in("GET", "/audit?action=nothing.*", null)).get("content")).isEmpty();
        assertThat(json(in("GET", "/audit?actor=dev", null)).get("content")).isNotEmpty();
        assertThat(json(in("GET", "/audit?actor=nobody", null)).get("content")).isEmpty();
    }

    @Test
    @DisplayName("the trail is paged")
    void paging() {
        for (int i = 0; i < 3; i++) {
            String slug = unique("db");
            connection(slug, "VAULT");
            in("POST", "/secrets/" + slug + "/rotated", null);
        }

        JsonNode first = json(in("GET", "/audit?size=2&page=0", null));
        JsonNode second = json(in("GET", "/audit?size=2&page=1", null));

        assertThat(first.get("content")).hasSize(2);
        assertThat(second.get("content").get(0).get("seq").asLong()).isLessThan(first.get("content").get(1).get("seq").asLong());
    }

    @Test
    @DisplayName("verify says the chain is intact")
    void verify() {
        assertThat(json(in("GET", "/audit/verify", null)).get("intact").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the export is CSV, defuses formulas, and records the hash of exactly what it sent")
    void export() throws Exception {
        String slug = unique("db");
        connection(slug, "VAULT");
        in("POST", "/secrets/" + slug + "/rotated", null);

        HttpResponse<String> response = in("GET", "/audit/export?format=csv", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(response.body()).startsWith("seq,id,at,actor,actor_type,action,target_type,target_id,hash,before,after\n");
        assertThat(response.body()).contains("secret.rotated");
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(response.body().getBytes(StandardCharsets.UTF_8)));
        assertThat(response.headers().firstValue("X-LQM-Export-Sha256")).contains(expected);

        String logged = jdbc.sql("select cast(after as text) from audit_event where action = 'audit.export' order by seq desc limit 1")
                .query(String.class).single();
        assertThat(logged).contains(expected);
        assertThat(in("GET", "/audit/export?format=xml", null).statusCode()).as("only csv and pdf").isEqualTo(422);
    }

    @Test
    @DisplayName("the PDF export is a real PDF, and the hash of exactly those bytes is in the log and in the header")
    void exportPdf() throws Exception {
        String slug = unique("db");
        connection(slug, "VAULT");
        in("POST", "/secrets/" + slug + "/rotated", null);

        java.net.http.HttpResponse<byte[]> response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + API + "/audit/export?format=pdf"))
                        .header("X-LQM-Workspace", workspace).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/pdf");
        assertThat(response.headers().firstValue("Content-Disposition").orElse("")).contains("lqm-audit.pdf");
        byte[] bytes = response.body();
        assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertThat(response.headers().firstValue("X-LQM-Export-Sha256")).contains(expected);
        String logged = jdbc.sql("select cast(after as text) from audit_event where action = 'audit.export' order by seq desc limit 1")
                .query(String.class).single();
        assertThat(logged).contains(expected).contains("pdf");

        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(bytes)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertThat(text).contains("Trilha de auditoria", "secret.rotated", "ÍNTEGRA");
        }
    }
}
