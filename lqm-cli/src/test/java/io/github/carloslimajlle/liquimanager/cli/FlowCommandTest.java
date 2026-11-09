package io.github.carloslimajlle.liquimanager.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import picocli.CommandLine;

/** {@code lqm flow} end to end: real Liquibase on H2, a stub LiquiManager, real stages. */
class FlowCommandTest {

    private static final String CHANGELOG = """
            --liquibase formatted sql

            --changeset p.ricardo:SUP-1-001-create-t
            create table t (id int primary key);
            --rollback drop table t;
            """;

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> reports = new CopyOnWriteArrayList<>();
    private String h2;

    @BeforeEach
    void start() throws IOException {
        h2 = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Files.createDirectories(dir.resolve("db"));
        Files.writeString(dir.resolve("db/master.sql"), CHANGELOG);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/policies/effective", exchange -> {
            byte[] body = "{\"package\":\"default\",\"etag\":\"e\",\"blockThreshold\":4,\"checks\":[],\"exceptions\":[]}"
                    .getBytes(StandardCharsets.UTF_8);
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
    void stop() {
        server.stop(0);
    }

    private int flow(String yaml, String env, String... extra) throws IOException {
        Files.writeString(dir.resolve("lqm.flow.yaml"), yaml);
        List<String> args = new ArrayList<>(List.of("flow", "--workdir", dir.toString(), "--url", h2, "--username", "sa",
                "--password", "", "--changelog-file", "db/master.sql", "--project", "gate", "--env", env,
                "--lqm-url", "http://127.0.0.1:" + server.getAddress().getPort(), "--lqm-token", "lqm_x_y",
                "--buffer-dir", dir.resolve("buffer").toString()));
        args.addAll(List.of(extra));
        PrintStream out = System.out;
        PrintStream err = System.err;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            return new CommandLine(new Main()).execute(args.toArray(new String[0]));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
    }

    private List<String> commandsReported() {
        return reports.stream().map(r -> r.replaceAll(".*\"command\":\"([^\"]+)\".*", "$1")).toList();
    }

    @Test
    @DisplayName("runs the stages in order, each as its own operation, and one flow operation that ties them together")
    void runsEveryStage() throws IOException {
        int exit = flow("""
                version: 1
                variables:
                  release: ${RELEASE_FOR_TEST:-snapshot}
                stages:
                  - name: checks
                    run: checks run
                  - name: preview
                    run: update-sql
                    artifact: target/lqm/update.sql
                  - name: update
                    run: update
                  - name: tag
                    run: tag ${release}
                """, "HML");

        assertThat(exit).isZero();
        assertThat(commandsReported()).containsExactly("checks run", "update-sql", "update", "tag", "flow");
        String flowReport = reports.get(4);
        String flowId = flowReport.replaceAll(".*\"operationId\":\"([^\"]+)\".*", "$1");
        assertThat(flowReport).contains("\"status\":\"SUCCESS\"").doesNotContain("\"changesets\":[{");
        assertThat(reports.subList(0, 4)).allSatisfy(r -> assertThat(r).contains("\"parentOperationId\":\"" + flowId + "\""));
        assertThat(reports.get(2)).contains("\"outcome\":\"EXECUTED\"");
        assertThat(reports.get(3)).contains("\"tag\":\"snapshot\"");
        assertThat(Files.readString(dir.resolve("target/lqm/update.sql"))).containsIgnoringCase("create table t");
    }

    @Test
    @DisplayName("a stage whose condition does not hold is skipped, and its artifact is not written")
    void skipsByCondition() throws IOException {
        int exit = flow("""
                version: 1
                stages:
                  - name: preview
                    run: update-sql
                    when: env in [PRD]
                    artifact: target/lqm/update.sql
                  - name: update
                    run: update
                """, "HML");

        assertThat(exit).isZero();
        assertThat(commandsReported()).containsExactly("update", "flow");
        assertThat(dir.resolve("target/lqm/update.sql")).doesNotExist();
    }

    @Test
    @DisplayName("a failing stage stops the flow with its exit code, and the stages after it never run")
    void stopsOnFailure() throws IOException {
        int exit = flow("""
                version: 1
                stages:
                  - name: boom
                    run: rollback-one-changeset --changeset-id SUP-NOT-THERE
                  - name: update
                    run: update
                """, "HML");

        assertThat(exit).isEqualTo(1);
        assertThat(commandsReported()).containsExactly("rollback-one-changeset", "flow");
        assertThat(reports.get(1)).contains("\"status\":\"FAILURE\"", "stopped at stage boom");
    }

    @Test
    @DisplayName("a stage marked onFailure: continue fails without failing the flow")
    void continuesOnFailure() throws IOException {
        int exit = flow("""
                version: 1
                stages:
                  - name: optional
                    run: rollback-one-changeset --changeset-id SUP-NOT-THERE
                    onFailure: continue
                  - name: update
                    run: update
                """, "HML");

        assertThat(exit).isZero();
        assertThat(commandsReported()).containsExactly("rollback-one-changeset", "update", "flow");
        assertThat(reports.get(2)).contains("\"status\":\"SUCCESS\"");
    }

    @Test
    @DisplayName("a flow that names something it cannot run is refused before ANY stage runs")
    void refusesBeforeRunning() throws IOException {
        int exit = flow("""
                version: 1
                stages:
                  - name: update
                    run: update
                  - name: drift
                    run: diff --reference=snapshot
                """, "HML");

        assertThat(exit).isEqualTo(2);
        assertThat(reports).as("nothing ran, so nothing was reported").isEmpty();
    }

    @Test
    @DisplayName("a missing or unreadable flow file is a usage error")
    void missingFile() throws IOException {
        Files.writeString(dir.resolve("lqm.flow.yaml"), "x");
        Files.delete(dir.resolve("lqm.flow.yaml"));
        PrintStream err = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            assertThat(new CommandLine(new Main()).execute("flow", "--workdir", dir.toString(), "--url", h2,
                    "--changelog-file", "db/master.sql")).isEqualTo(2);
        } finally {
            System.setErr(err);
        }
    }
}
