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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.carloslimajlle.liquimanager.common.LqmException;

/** Vault with AppRole: the resolver logs in by itself, reuses the token, logs in again when refused, and sends the namespace. */
class VaultAppRoleTest {

    private HttpServer server;
    private final AtomicInteger logins = new AtomicInteger();
    private final AtomicInteger reads = new AtomicInteger();
    private final List<String> loginBodies = new CopyOnWriteArrayList<>();
    private final List<String> readTokens = new CopyOnWriteArrayList<>();
    private final List<String> namespaces = new CopyOnWriteArrayList<>();
    private volatile int loginStatus = 200;
    private volatile int leaseSeconds = 3600;
    /** The token Vault accepts right now; reading with any other is a 403. */
    private volatile String accepted = "tok-1";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/auth/approle/login", exchange -> {
            logins.incrementAndGet();
            loginBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            namespaces.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Vault-Namespace")));
            reply(exchange, loginStatus, "{\"auth\":{\"client_token\":\"" + accepted + "\",\"lease_duration\":" + leaseSeconds + "}}");
        });
        server.createContext("/v1/secret/", exchange -> {
            reads.incrementAndGet();
            String token = exchange.getRequestHeaders().getFirst("X-Vault-Token");
            readTokens.add(String.valueOf(token));
            namespaces.add(String.valueOf(exchange.getRequestHeaders().getFirst("X-Vault-Namespace")));
            if (!accepted.equals(token)) {
                reply(exchange, 403, "{\"errors\":[\"permission denied\"]}");
                return;
            }
            reply(exchange, 200, "{\"data\":{\"data\":{\"username\":\"app\",\"password\":\"pw\"}}}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private SecretResolver resolver(String namespace) {
        String url = "http://127.0.0.1:" + server.getAddress().getPort();
        return new SecretResolver(Duration.ofMillis(1), url, "", namespace, "role-1", "secret-1", "approle", "", "", "", "");
    }

    @Test
    @DisplayName("it logs in with the role, reads with the token it got, and keeps the token for the next read")
    void logsInOnceAndReuses() {
        SecretResolver resolver = resolver("");

        assertThat(resolver.resolve("VAULT", "secret/data/a").password()).isEqualTo("pw");
        resolver.evictAll();
        assertThat(resolver.resolve("VAULT", "secret/data/b").password()).isEqualTo("pw");

        assertThat(logins.get()).isEqualTo(1);
        assertThat(loginBodies.get(0)).contains("\"role_id\":\"role-1\"").contains("\"secret_id\":\"secret-1\"");
        assertThat(readTokens).containsOnly("tok-1");
        assertThat(resolver.vaultLoginHeld()).isTrue();
    }

    @Test
    @DisplayName("when Vault refuses the token it logs in again once and the read goes through")
    void relogsInWhenRefused() {
        SecretResolver resolver = resolver("");
        resolver.resolve("VAULT", "secret/data/a");
        accepted = "tok-2";
        resolver.evictAll();

        assertThat(resolver.resolve("VAULT", "secret/data/a").username()).isEqualTo("app");

        assertThat(logins.get()).isEqualTo(2);
        assertThat(readTokens).containsExactly("tok-1", "tok-1", "tok-2");
    }

    @Test
    @DisplayName("a token with a short lease is replaced before it is used past it")
    void renewsBeforeExpiry() throws InterruptedException {
        leaseSeconds = 6;
        SecretResolver resolver = resolver("");
        resolver.resolve("VAULT", "secret/data/a");
        assertThat(resolver.vaultLoginHeld()).isTrue();

        accepted = "tok-2";
        Thread.sleep(5200);
        resolver.evictAll();
        resolver.resolve("VAULT", "secret/data/a");

        assertThat(logins.get()).as("80% of 6 s is under 5.2 s").isEqualTo(2);
        assertThat(readTokens.get(readTokens.size() - 1)).isEqualTo("tok-2");
    }

    @Test
    @DisplayName("the namespace goes on the login and on the read")
    void sendsNamespace() {
        resolver("team-a/lqm").resolve("VAULT", "secret/data/a");

        assertThat(namespaces).containsOnly("team-a/lqm");
    }

    @Test
    @DisplayName("a refused login is LQM-050 with only the status, and no token is kept")
    void loginFailure() {
        loginStatus = 400;
        SecretResolver resolver = resolver("");

        assertThatThrownBy(() -> resolver.resolve("VAULT", "secret/data/a")).isInstanceOf(LqmException.class)
                .hasMessageContaining("login answered 400").hasMessageNotContaining("secret-1").hasMessageNotContaining("role-1");
        assertThat(resolver.vaultLoginHeld()).isFalse();
        assertThat(reads.get()).isZero();
    }
}
