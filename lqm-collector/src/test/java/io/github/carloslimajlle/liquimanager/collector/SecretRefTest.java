package io.github.carloslimajlle.liquimanager.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** The database credentials a deploy job fetches itself, from each provider, and the ways it can fail without printing a value. */
class SecretRefTest {

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "{}";
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + "|" + exchange.getRequestHeaders().getFirst("X-Vault-Token")
                    + "|" + exchange.getRequestHeaders().getFirst("X-Vault-Namespace") + "|" + exchange.getRequestHeaders().getFirst("X-Consul-Token")
                    + "|" + exchange.getRequestHeaders().getFirst("Authorization") + "|" + sent);
            String answer = exchange.getRequestURI().getPath().endsWith("/login")
                    ? "{\"auth\":{\"client_token\":\"approle-token\",\"lease_duration\":3600}}" : body;
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    @DisplayName("Vault KV v2 and v1 with a token, with the namespace when there is one")
    void vaultToken() {
        body = "{\"data\":{\"data\":{\"username\":\"app\",\"password\":\"s3cret\"},\"metadata\":{}}}";

        SecretRef.Credentials credentials = SecretRef.resolve("vault:secret/data/lqm/core-hml",
                Map.of("VAULT_ADDR", url(), "VAULT_TOKEN", "tok", "VAULT_NAMESPACE", "team-a"));

        assertThat(credentials.username()).isEqualTo("app");
        assertThat(credentials.password()).isEqualTo("s3cret");
        assertThat(requests.get(0)).startsWith("GET /v1/secret/data/lqm/core-hml|tok|team-a|");

        body = "{\"data\":{\"username\":\"v1\",\"password\":\"pw\"}}";
        assertThat(SecretRef.resolve("vault:secret/lqm/x", Map.of("VAULT_ADDR", url(), "VAULT_TOKEN", "tok")).password()).isEqualTo("pw");
    }

    @Test
    @DisplayName("Vault with AppRole logs in first and reads with the token it got")
    void vaultAppRole() {
        body = "{\"data\":{\"data\":{\"username\":\"app\",\"password\":\"pw\"}}}";

        SecretRef.resolve("vault:secret/data/a", Map.of("VAULT_ADDR", url(), "VAULT_ROLE_ID", "r", "VAULT_SECRET_ID", "s"));

        assertThat(requests.get(0)).startsWith("POST /v1/auth/approle/login").contains("\"role_id\":\"r\"");
        assertThat(requests.get(1)).startsWith("GET /v1/secret/data/a|approle-token");
    }

    @Test
    @DisplayName("Consul reads the JSON in the key, and Azure the JSON in the secret value, each with its own token")
    void consulAndAzure() {
        body = "{\"username\":\"c\",\"password\":\"p\"}";
        assertThat(SecretRef.resolve("consul:lqm/core-prd", Map.of("CONSUL_HTTP_ADDR", url(), "CONSUL_HTTP_TOKEN", "ct")).username()).isEqualTo("c");
        assertThat(requests.get(0)).startsWith("GET /v1/kv/lqm/core-prd?raw|null|null|ct");

        body = "{\"value\":\"{\\\"username\\\":\\\"az\\\",\\\"password\\\":\\\"zz\\\"}\"}";
        assertThat(SecretRef.resolve("azure:core-hml", Map.of("AZURE_KEYVAULT_URL", url(), "AZURE_KEYVAULT_TOKEN", "at")).password()).isEqualTo("zz");
        assertThat(requests.get(1)).contains("/secrets/core-hml?api-version=7.4").endsWith("Bearer at|");
    }

    @Test
    @DisplayName("env: reads PREFIX_USERNAME and PREFIX_PASSWORD, the prefix made safe")
    void environment() {
        SecretRef.Credentials credentials = SecretRef.resolve("env:core-hml", Map.of("CORE_HML_USERNAME", "u", "CORE_HML_PASSWORD", "hunter2-xyz"));

        assertThat(credentials.username()).isEqualTo("u");
        assertThat(credentials.password()).isEqualTo("hunter2-xyz");
        assertThat(credentials.toString()).doesNotContain("hunter2-xyz");
    }

    @Test
    @DisplayName("a failure names the provider and the status, never a value or the response body")
    void failures() {
        body = "{\"errors\":[\"secret/data/lqm/core-hml: permission denied, token tok-123\"]}";
        status = 403;

        assertThatThrownBy(() -> SecretRef.resolve("vault:secret/data/lqm/core-hml", Map.of("VAULT_ADDR", url(), "VAULT_TOKEN", "tok-123")))
                .isInstanceOf(SecretRef.Failure.class).hasMessage("vault answered 403");

        status = 200;
        body = "{\"data\":{\"data\":{\"other\":1}}}";
        assertThatThrownBy(() -> SecretRef.resolve("vault:secret/data/a", Map.of("VAULT_ADDR", url(), "VAULT_TOKEN", "t")))
                .hasMessage("vault secret has no username or password");
        assertThatThrownBy(() -> SecretRef.resolve("vault:secret/data/a", Map.of("VAULT_ADDR", url())))
                .hasMessageContaining("VAULT_TOKEN");
        assertThatThrownBy(() -> SecretRef.resolve("vault:x", Map.of("VAULT_TOKEN", "t"))).hasMessageContaining("VAULT_ADDR");
        assertThatThrownBy(() -> SecretRef.resolve("nothing", Map.of())).hasMessageContaining("provider:reference");
        assertThatThrownBy(() -> SecretRef.resolve("ldap:x", Map.of())).hasMessageContaining("unknown secret provider");
        assertThatThrownBy(() -> SecretRef.resolve("vault:x", Map.of("VAULT_ADDR", "http://127.0.0.1:1", "VAULT_TOKEN", "t")))
                .hasMessage("could not reach vault");
    }
}
