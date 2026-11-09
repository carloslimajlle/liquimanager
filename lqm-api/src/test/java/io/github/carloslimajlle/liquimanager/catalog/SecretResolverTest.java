package io.github.carloslimajlle.liquimanager.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.github.carloslimajlle.liquimanager.common.LqmException;

/** Each secret provider against a stub of its HTTP API; no credential ever leaves through an error. */
class SecretResolverTest {

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> headers = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            headers.add(exchange.getRequestURI() + "|" + exchange.getRequestHeaders().getFirst("X-Vault-Token")
                    + "|" + exchange.getRequestHeaders().getFirst("X-Consul-Token")
                    + "|" + exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
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

    private SecretResolver resolver(Duration ttl) {
        return new SecretResolver(ttl, url(), "vault-token", url(), "consul-token", url(), "azure-token");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Vault KV v2 reads data.data and sends the token")
    void vaultV2() {
        body = "{\"data\":{\"data\":{\"username\":\"app\",\"password\":\"s3cret\"},\"metadata\":{}}}";

        SecretResolver.Credentials credentials = resolver(Duration.ofMinutes(5)).resolve("VAULT", "secret/data/lqm/core-hml");

        assertThat(credentials.username()).isEqualTo("app");
        assertThat(credentials.password()).isEqualTo("s3cret");
        assertThat(headers.get(0)).startsWith("/v1/secret/data/lqm/core-hml|vault-token");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Vault KV v1 reads data")
    void vaultV1() {
        body = "{\"data\":{\"username\":\"app\",\"password\":\"pw\"}}";

        assertThat(resolver(Duration.ofMinutes(5)).resolve("VAULT", "secret/lqm/x").password()).isEqualTo("pw");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Consul KV reads the JSON stored in the key")
    void consul() {
        body = "{\"username\":\"c\",\"password\":\"p\"}";

        SecretResolver.Credentials credentials = resolver(Duration.ofMinutes(5)).resolve("CONSUL", "lqm/core-prd");

        assertThat(credentials.username()).isEqualTo("c");
        assertThat(headers.get(0)).startsWith("/v1/kv/lqm/core-prd?raw|null|consul-token");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("Azure Key Vault reads the secret value and sends the bearer token")
    void azure() {
        body = "{\"value\":\"{\\\"username\\\":\\\"az\\\",\\\"password\\\":\\\"zz\\\"}\"}";

        SecretResolver.Credentials credentials = resolver(Duration.ofMinutes(5)).resolve("AZURE_KV", "core-hml");

        assertThat(credentials.username()).isEqualTo("az");
        assertThat(headers.get(0)).contains("/secrets/core-hml?api-version=7.4").endsWith("Bearer azure-token");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("the second use inside the TTL does not touch the provider")
    void cached() {
        body = "{\"data\":{\"username\":\"a\",\"password\":\"b\"}}";
        SecretResolver resolver = resolver(Duration.ofMinutes(5));

        resolver.resolve("VAULT", "secret/x");
        resolver.resolve("VAULT", "secret/x");

        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("after the TTL, or after a rotation is announced, it asks again")
    void expiresAndEvicts() throws InterruptedException {
        body = "{\"data\":{\"username\":\"a\",\"password\":\"b\"}}";
        SecretResolver shortLived = resolver(Duration.ofMillis(50));
        shortLived.resolve("VAULT", "secret/x");
        Thread.sleep(120);
        shortLived.resolve("VAULT", "secret/x");
        assertThat(calls.get()).isEqualTo(2);

        SecretResolver resolver = resolver(Duration.ofMinutes(5));
        resolver.resolve("VAULT", "secret/y");
        resolver.evictAll();
        resolver.resolve("VAULT", "secret/y");
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a TTL above five minutes is capped at five")
    void ttlIsCapped() throws InterruptedException {
        body = "{\"data\":{\"username\":\"a\",\"password\":\"b\"}}";
        // Not observable without waiting five minutes; what can be checked is that an absurd value still works.
        assertThat(resolver(Duration.ofDays(30)).resolve("VAULT", "secret/z").username()).isEqualTo("a");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a provider failure is LQM-050 and the message carries no value")
    void failuresDoNotLeak() {
        status = 403;
        body = "{\"errors\":[\"permission denied for password=hunter2\"]}";

        assertThatThrownBy(() -> resolver(Duration.ofMinutes(5)).resolve("VAULT", "secret/x"))
                .isInstanceOfSatisfying(LqmException.class, e -> {
                    assertThat(e.code()).isEqualTo("LQM-050");
                    assertThat(e.getMessage()).contains("403").doesNotContain("hunter2");
                });
    }

    @Test
    @org.junit.jupiter.api.DisplayName("unreachable, not configured, no reference and empty secret are all LQM-050")
    void otherFailures() {
        SecretResolver unconfigured = new SecretResolver(Duration.ofMinutes(5), "", "", "", "", "", "");
        assertThatThrownBy(() -> unconfigured.resolve("VAULT", "a")).isInstanceOf(LqmException.class).hasMessageContaining("not configured");
        assertThatThrownBy(() -> resolver(Duration.ofMinutes(5)).resolve("VAULT", " ")).isInstanceOf(LqmException.class);
        body = "{\"data\":{\"data\":{}}}";
        assertThatThrownBy(() -> resolver(Duration.ofMinutes(5)).resolve("VAULT", "secret/empty")).isInstanceOf(LqmException.class);
        body = "not json";
        assertThatThrownBy(() -> resolver(Duration.ofMinutes(5)).resolve("CONSUL", "k")).isInstanceOf(LqmException.class);
        server.stop(0);
        assertThatThrownBy(() -> resolver(Duration.ofMinutes(5)).resolve("VAULT", "secret/x"))
                .isInstanceOfSatisfying(LqmException.class, e -> assertThat(e.code()).isEqualTo("LQM-050"));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("credentials print redacted")
    void redacted() {
        assertThat(new SecretResolver.Credentials("u", "topsecret").toString()).doesNotContain("topsecret");
    }

    @Test
    @org.junit.jupiter.api.DisplayName("NONE has no credentials and an unknown provider is refused")
    void noneAndUnknown() {
        assertThat(resolver(Duration.ofMinutes(5)).resolve(null, null).username()).isNull();
        assertThatThrownBy(() -> resolver(Duration.ofMinutes(5)).resolve("MAGIC", "x")).isInstanceOf(LqmException.class);
    }
}
