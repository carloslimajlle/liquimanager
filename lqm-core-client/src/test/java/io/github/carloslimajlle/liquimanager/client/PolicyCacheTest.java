package io.github.carloslimajlle.liquimanager.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

/** The policies a collector holds: used for five minutes, confirmed with an ETag after that, and kept for when LiquiManager is gone. */
class PolicyCacheTest {

    private static final String BODY = "{\"package\":\"default\",\"etag\":\"v1\",\"blockThreshold\":3,"
            + "\"checks\":[{\"code\":\"RollbackRequired\",\"kind\":\"BUILTIN\",\"severity\":2,\"parameters\":{}}],\"exceptions\":[]}";

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger status = new AtomicInteger(200);
    private final List<String> conditions = new CopyOnWriteArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-04T10:00:00Z"));

    @TempDir
    Path dir;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/policies/effective", exchange -> {
            calls.incrementAndGet();
            conditions.add(String.valueOf(exchange.getRequestHeaders().getFirst("If-None-Match")));
            int code = status.get();
            if (code == 200 && "\"v1\"".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                code = 304;
            }
            byte[] body = code == 200 ? BODY.getBytes(StandardCharsets.UTF_8) : new byte[0];
            exchange.getResponseHeaders().add("ETag", "\"v1\"");
            exchange.sendResponseHeaders(code, code == 200 ? body.length : -1);
            if (code == 200) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private LqmClient client() {
        LqmConfig config = new LqmConfig("http://127.0.0.1:" + server.getAddress().getPort(), "lqm_a_b", Duration.ofSeconds(2), dir);
        return new LqmClient(config, now::get);
    }

    @Test
    @DisplayName("a second ask inside five minutes does not touch LiquiManager; after that it asks with the ETag and a 304 keeps the held policies")
    void fiveMinutes() {
        LqmClient client = client();

        assertThat(client.effectivePolicies("gate", "HML", "db/master.xml")).isPresent();
        now.set(now.get().plusSeconds(120));
        assertThat(client.effectivePolicies("gate", "HML", "db/master.xml")).isPresent();
        assertThat(calls.get()).as("inside the five minutes").isEqualTo(1);

        now.set(now.get().plus(Duration.ofMinutes(4)));
        assertThat(client.effectivePolicies("gate", "HML", "db/master.xml").get().blockThreshold()).isEqualTo(3);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(conditions.get(1)).as("it asked with what it holds").isEqualTo("\"v1\"");

        now.set(now.get().plusSeconds(60));
        client.effectivePolicies("gate", "HML", "db/master.xml");
        assertThat(calls.get()).as("the 304 renewed the five minutes").isEqualTo(2);
    }

    @Test
    @DisplayName("another project, environment or changelog is another answer")
    void keyed() {
        LqmClient client = client();

        client.effectivePolicies("gate", "HML", "db/master.xml");
        client.effectivePolicies("gate", "PRD", "db/master.xml");
        client.effectivePolicies("other", "HML", "db/master.xml");

        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("LiquiManager gone: a run that starts later reads the file the earlier one left, up to a day old, and no older")
    void offlineFallback() {
        client().effectivePolicies("gate", "HML", "db/master.xml");
        assertThat(dir.resolve("policies")).isDirectoryContaining(path -> path.toString().endsWith(".json"));
        server.stop(0);

        now.set(now.get().plus(Duration.ofHours(3)));
        assertThat(client().effectivePolicies("gate", "HML", "db/master.xml")).as("a new process, three hours later").isPresent();

        now.set(now.get().plus(Duration.ofHours(30)));
        assertThat(client().effectivePolicies("gate", "HML", "db/master.xml")).as("past a day: a different set of rules by now").isEmpty();
    }

    @Test
    @DisplayName("a server error stands in with the held answer; fail-closed never takes one and always asks")
    void errorsAndFailClosed() {
        LqmClient client = client();
        client.effectivePolicies("gate", "HML", "db/master.xml");
        now.set(now.get().plus(Duration.ofMinutes(10)));
        status.set(503);

        assertThat(client.effectivePolicies("gate", "HML", "db/master.xml")).as("held answer").isPresent();
        assertThat(client.effectivePolicies("gate", "HML", "db/master.xml", false)).as("fail-closed: a failure is a failure").isEmpty();

        status.set(200);
        int before = calls.get();
        assertThat(client.effectivePolicies("gate", "HML", "db/master.xml", false)).isPresent();
        assertThat(calls.get()).as("it asked although the held answer was fresh").isGreaterThan(before);
    }

    @Test
    @DisplayName("an unreadable file is ignored, and nothing is cached without a buffer directory")
    void robust() throws IOException {
        Files.createDirectories(dir.resolve("policies"));
        Files.writeString(dir.resolve("policies").resolve(PolicyCache.key("gate", "HML", null) + ".json"), "{ not json");

        assertThat(client().effectivePolicies("gate", "HML", null)).as("asks, because the file said nothing usable").isPresent();

        LqmClient noDisk = new LqmClient(new LqmConfig("http://127.0.0.1:" + server.getAddress().getPort(), "lqm_a_b", Duration.ofSeconds(2), null), now::get);
        int before = calls.get();
        noDisk.effectivePolicies("gate", "DEV", null);
        noDisk.effectivePolicies("gate", "DEV", null);
        assertThat(calls.get() - before).as("memory only still caches").isEqualTo(1);
    }
}
