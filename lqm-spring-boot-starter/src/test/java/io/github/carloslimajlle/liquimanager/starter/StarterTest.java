package io.github.carloslimajlle.liquimanager.starter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sun.net.httpserver.HttpServer;

import liquibase.integration.spring.SpringLiquibase;

/**
 * The starter inside a real Spring Boot 3.4 context running Liquibase 4.x, which is what a
 * typical Boot 3.4 service looks like. The collector and the engine are compiled against Liquibase
 * 5.x, so this is also where that compatibility (RNF-15) is actually exercised.
 */
class StarterTest {

    private HttpServer server;
    private final List<String> reports = new CopyOnWriteArrayList<>();
    private String blockThreshold = "4";
    private String rollbackSeverity = "4";

    @TempDir
    Path buffer;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/policies/effective", exchange -> {
            byte[] body = ("{\"package\":\"default\",\"etag\":\"e\",\"blockThreshold\":" + blockThreshold
                    + ",\"checks\":[{\"code\":\"RollbackRequired\",\"kind\":\"BUILTIN\",\"severity\":" + rollbackSeverity
                    + ",\"parameters\":{}}],\"exceptions\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/ingest/operations", exchange -> {
            reports.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ApplicationContextRunner runner(String... extra) {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        LqmLiquibaseAutoConfiguration.class, LiquibaseAutoConfiguration.class))
                .withPropertyValues(
                        "spring.datasource.url=" + url,
                        "spring.liquibase.change-log=classpath:db/changelog.xml",
                        "spring.liquibase.contexts=app",
                        "lqm.url=http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1",
                        "lqm.token=lqm_abcd1234_secret",
                        "lqm.project=gate",
                        "lqm.environment=DEV",
                        "lqm.timeout=1s",
                        "lqm.buffer-dir=" + buffer.toString().replace('\\', '/'));
        return runner.withPropertyValues(extra);
    }

    private static boolean hasClienteTable(javax.sql.DataSource dataSource) {
        try {
            new JdbcTemplate(dataSource).queryForObject("select count(*) from cliente", Integer.class);
            return true;
        } catch (org.springframework.dao.DataAccessException e) {
            return false;
        }
    }

    @Test
    void replacesBootsLiquibaseUpdatesAndReports() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SpringLiquibase.class)).isInstanceOf(LqmSpringLiquibase.class);
            assertThat(hasClienteTable(context.getBean(javax.sql.DataSource.class))).isTrue();
        });

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0)).contains("\"command\":\"update\"", "\"source\":\"startup\"",
                "\"status\":\"SUCCESS\"", "SUP-1-001-create-cliente", "\"collector\":\"starter\"",
                "\"project\":\"gate\"");
        assertThat(reports.get(0)).doesNotContain("password");
    }

    @Test
    void enforceBlocksTheStartupBeforeAnythingRuns() {
        runner("lqm.enforce=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("blocked by LiquiManager policy")
                    .hasStackTraceContaining("RollbackRequired");
        });

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0)).contains("\"status\":\"BLOCKED\"", "\"result\":\"BLOCKED\"");
    }

    @Test
    void enforceLetsAWarningThrough() {
        rollbackSeverity = "2";

        runner("lqm.enforce=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(hasClienteTable(context.getBean(javax.sql.DataSource.class))).isTrue();
        });

        assertThat(reports.get(0)).contains("\"status\":\"SUCCESS\"", "\"gateResult\":\"WARNED\"");
    }

    @Test
    void withoutEnforceAViolationNeverBlocks() {
        runner().run(context -> assertThat(context).hasNotFailed());

        assertThat(reports).hasSize(1);
    }

    @Test
    void aLiquiManagerThatIsDownDoesNotStopTheService() throws IOException {
        int port = server.getAddress().getPort();
        server.stop(0);

        runner("lqm.url=http://127.0.0.1:" + port, "lqm.enforce=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(hasClienteTable(context.getBean(javax.sql.DataSource.class))).isTrue();
        });

        try (var files = Files.list(buffer)) {
            assertThat(files.count()).isEqualTo(1);
        }
    }

    @Test
    void failClosedRefusesToStartWhenLiquiManagerIsDown() {
        int port = server.getAddress().getPort();
        server.stop(0);

        runner("lqm.url=http://127.0.0.1:" + port, "lqm.fail-closed=true", "lqm.enforce=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("lqm.fail-closed=true");
        });
    }

    @Test
    void aFailingChangesetIsReportedAsFailureAndStillFailsTheStartup() {
        runner("spring.liquibase.change-log=classpath:db/broken.xml", "spring.liquibase.contexts=").run(context -> {
            assertThat(context).hasFailed();
        });

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0)).contains("\"status\":\"FAILURE\"", "\"outcome\":\"FAILED\"");
    }

    @Test
    void disabledMeansBootsOwnLiquibaseRunsAndNothingIsReported() {
        runner("lqm.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SpringLiquibase.class)).isNotInstanceOf(LqmSpringLiquibase.class);
            assertThat(hasClienteTable(context.getBean(javax.sql.DataSource.class))).isTrue();
        });

        assertThat(reports).isEmpty();
    }
}
