package io.github.carloslimajlle.liquimanager.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** Fail-open delivery: what the client does when LiquiManager answers, errs or is gone. */
class LqmClientTest {

    private HttpServer server;
    private final AtomicInteger ingestStatus = new AtomicInteger(201);
    private final List<String> received = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();

    @TempDir
    Path buffer;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/ingest/operations", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes()));
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(ingestStatus.get(), -1);
            exchange.close();
        });
        server.createContext("/api/v1/policies/effective", exchange -> {
            byte[] body = ("{\"package\":\"default\",\"etag\":\"e1\",\"blockThreshold\":3,"
                    + "\"checks\":[{\"code\":\"RollbackRequired\",\"kind\":\"BUILTIN\",\"severity\":2,\"parameters\":{}}],"
                    + "\"exceptions\":[],\"somethingNew\":true}").getBytes();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LqmClient client() {
        return new LqmClient(new LqmConfig("http://127.0.0.1:" + server.getAddress().getPort(),
                "lqm_abcd1234_secret", Duration.ofSeconds(2), buffer));
    }

    private static IngestPayload payload() {
        return new IngestPayload(UUID.randomUUID(), "update", "SUCCESS", "cli-local", "tester",
                Instant.now(), Instant.now(), "gate", "DEV", null, null, null, null,
                new IngestPayload.Connection(null, "jdbc:postgresql://db/app", "postgresql", null),
                null, null, null, null, null, List.of(), null, null, null, null, null);
    }

    private long buffered() throws IOException {
        try (var files = Files.list(buffer)) {
            return files.count();
        }
    }

    @Test
    void sendsTheReportWithTheBearerToken() throws IOException {
        assertThat(client().report(payload())).isTrue();

        assertThat(received).hasSize(1);
        assertThat(received.get(0)).contains("\"command\":\"update\"").doesNotContain("\"deploymentId\"");
        assertThat(authHeaders).containsExactly("Bearer lqm_abcd1234_secret");
        assertThat(buffered()).isZero();
    }

    @Test
    void aServerErrorKeepsTheReportForTheNextRun() throws IOException {
        ingestStatus.set(503);
        LqmClient client = client();

        assertThat(client.report(payload())).isFalse();
        assertThat(buffered()).isEqualTo(1);

        ingestStatus.set(201);
        assertThat(client.flushBuffer()).isEqualTo(1);
        assertThat(buffered()).isZero();
    }

    @Test
    void theNextSuccessfulReportAlsoDeliversWhatWasWaiting() throws IOException {
        ingestStatus.set(500);
        LqmClient client = client();
        client.report(payload());
        assertThat(buffered()).isEqualTo(1);

        ingestStatus.set(201);
        assertThat(client.report(payload())).isTrue();

        assertThat(buffered()).isZero();
        assertThat(received).hasSize(3);
    }

    @Test
    void aReportTheApiRejectsIsNotKeptForever() throws IOException {
        ingestStatus.set(422);

        assertThat(client().report(payload())).isFalse();

        assertThat(buffered()).isZero();
    }

    @Test
    void aBadTokenIsKeptBecauseARotatedTokenFixesIt() throws IOException {
        ingestStatus.set(401);

        assertThat(client().report(payload())).isFalse();

        assertThat(buffered()).isEqualTo(1);
    }

    @Test
    void anUnreachableServerNeverThrowsAndBuffers() throws IOException {
        int port = server.getAddress().getPort();
        server.stop(0);
        LqmClient client = new LqmClient(new LqmConfig("http://127.0.0.1:" + port, "lqm_x_y",
                Duration.ofMillis(500), buffer));

        assertThat(client.report(payload())).isFalse();
        assertThat(client.effectivePolicies("gate", "DEV", null)).isEmpty();
        assertThat(buffered()).isEqualTo(1);
    }

    @Test
    void readsTheEffectivePackageAndIgnoresFieldsItDoesNotKnow() {
        EffectivePolicies policies = client().effectivePolicies("gate", "DEV", null).orElseThrow();

        assertThat(policies.name()).isEqualTo("default");
        assertThat(policies.toPackage().blockThreshold()).isEqualTo(3);
        assertThat(policies.toPackage().checks()).extracting("code").containsExactly("RollbackRequired");
    }

    @Test
    void withoutUrlOrTokenItDoesNothing() {
        LqmClient client = new LqmClient(new LqmConfig(null, null, null, buffer));

        assertThat(client.isConfigured()).isFalse();
        assertThat(client.report(payload())).isFalse();
        assertThat(client.effectivePolicies("gate", "DEV", null)).isEmpty();
    }
}
