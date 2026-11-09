package io.github.carloslimajlle.liquimanager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import io.github.carloslimajlle.liquimanager.security.ServiceTokenService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Base for tests that need the real application against a REAL PostgreSQL.
 *
 * <p>Skipped unless LQM_TEST_DB_URL is set, so a plain {@code mvn verify} on a machine
 * without a database stays green; CI provides one. Every subclass shares this exact
 * configuration, so Spring builds ONE context for all of them instead of one each.
 *
 * <p>The database is shared and never cleaned: tests mint unique slugs and ids rather
 * than assuming an empty schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "LQM_TEST_DB_URL", matches = ".+")
public abstract class AbstractDatabaseTest {

    /** Seeded by seed/001-environments.sql. */
    protected static final UUID WORKSPACE = UUID.fromString("a5003659-317a-589a-9a01-2c9854fc6d72");
    protected static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    /**
     * The "e-mail service" every test context talks to: what it was sent, and what it should answer. One for the
     * whole run, because the Spring context (and so its property) is shared.
     */
    protected static final class EmailStub {
        public final java.util.List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        public volatile int status = 200;
        final com.sun.net.httpserver.HttpServer server;

        EmailStub() {
            try {
                server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/send", exchange -> {
                requests.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/send";
        }
    }

    protected static final EmailStub EMAIL = new EmailStub();

    /** The "GitLab" a promotion triggers: the forms it was sent, and what it should answer. */
    protected static final class GitlabStub {
        public final java.util.List<String> requests = new java.util.concurrent.CopyOnWriteArrayList<>();
        public final java.util.List<String> paths = new java.util.concurrent.CopyOnWriteArrayList<>();
        public volatile int status = 201;
        /** Repository files by path, and what was asked of them (path, query, token). */
        public final java.util.Map<String, String> files = new java.util.concurrent.ConcurrentHashMap<>();
        public final java.util.List<String> reads = new java.util.concurrent.CopyOnWriteArrayList<>();
        final com.sun.net.httpserver.HttpServer server;

        GitlabStub() {
            try {
                server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                if ("GET".equals(exchange.getRequestMethod()) && path.contains("/repository/files/")) {
                    // A repository file read: answered from `files`, keyed by the file path; 404 for anything else.
                    String file = path.substring(path.indexOf("/repository/files/") + "/repository/files/".length(),
                            path.lastIndexOf("/raw"));
                    reads.add(path + "?" + exchange.getRequestURI().getQuery() + " token="
                            + exchange.getRequestHeaders().getFirst("PRIVATE-TOKEN"));
                    // A version of the file at a ref is keyed "path@ref" and wins over the plain path, so a test can tell two commits apart.
                    String query = exchange.getRequestURI().getQuery();
                    String ref = query == null || !query.contains("ref=") ? "" : query.substring(query.indexOf("ref=") + 4).split("&")[0];
                    String content = files.containsKey(file + "@" + ref) ? files.get(file + "@" + ref) : files.get(file);
                    byte[] raw = (content == null ? "{}" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(content == null ? 404 : 200, raw.length);
                    exchange.getResponseBody().write(raw);
                    exchange.close();
                    return;
                }
                paths.add(path);
                requests.add(java.net.URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.UTF_8));
                byte[] answer = "{\"id\":4242,\"web_url\":\"http://gitlab.test/p/-/pipelines/4242\"}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, status / 100 == 2 ? answer.length : -1);
                if (status / 100 == 2) {
                    exchange.getResponseBody().write(answer);
                }
                exchange.close();
            });
            server.start();
        }

        String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }
    }

    protected static final GitlabStub GITLAB = new GitlabStub();

    @LocalServerPort
    protected int port;

    @Autowired
    protected ServiceTokenService tokens;

    @Autowired
    protected JdbcClient jdbc;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        // Never the application database: without the variable the tests would fill the one a person is using with their data.
        registry.add("spring.datasource.url", () -> envOr("LQM_TEST_DB_URL", "jdbc:postgresql://localhost:5432/lqm_test"));
        registry.add("spring.datasource.username", () -> envOr("LQM_TEST_DB_USERNAME", "lqm"));
        registry.add("spring.datasource.password", () -> envOr("LQM_TEST_DB_PASSWORD", "lqm"));
        registry.add("lqm.storage.dir", AbstractDatabaseTest::tempDir);
        registry.add("lqm.notifications.email.url", EMAIL::url);
        registry.add("lqm.gitlab.base-url", GITLAB::url);
        registry.add("lqm.gitlab.trigger-token", () -> "glptt-SECRET");
        registry.add("lqm.gitlab.read-token", () -> "glpat-READ-SECRET");
        // The ticks are driven by the tests that care about them; a real one would try to reach every placeholder host.
        registry.add("lqm.jobs.drift-tick-cron", () -> "-");
        registry.add("lqm.jobs.scanner-tick-cron", () -> "-");
        registry.add("lqm.jobs.metrics-recent-cron", () -> "-");
        registry.add("lqm.jobs.rollback-reaper-cron", () -> "-");
        registry.add("lqm.jobs.notifications-cron", () -> "-");
        registry.add("lqm.jobs.exception-expiry-cron", () -> "-");
        registry.add("lqm.jobs.metrics-full-cron", () -> "-");
    }

    private static String tempDir() {
        try {
            return Files.createTempDirectory("lqm-blobs").toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    // ----------------------------------------------------------------- helpers

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    protected String serviceToken(String... scopes) {
        return tokens.issue(WORKSPACE, unique("it"), List.of(scopes), null,
                Instant.now().plusSeconds(3600), "integration-test").token();
    }

    /** Sends a request; {@code bearer} and {@code body} may be null. */
    protected HttpResponse<String> call(String method, String path, String bearer, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** A GET with a bearer token and extra request headers (e.g. If-None-Match). */
    protected HttpResponse<String> getWith(String path, String bearer, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        headers.forEach(request::header);
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Like {@link #call} but scoped to a workspace through the X-LQM-Workspace header. */
    protected HttpResponse<String> callIn(String workspaceSlug, String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-LQM-Workspace", workspaceSlug)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Like {@link #callIn} but acting as a given user (the development-only X-LQM-User header). */
    protected HttpResponse<String> callAs(String user, String workspaceSlug, String method, String path, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-LQM-Workspace", workspaceSlug)
                .header("X-LQM-User", user)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        try {
            return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    protected HttpResponse<String> get(String path) {
        return call("GET", path, null, null);
    }

    protected HttpResponse<String> post(String path, String body) {
        return call("POST", path, null, body);
    }

    protected JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }
}
