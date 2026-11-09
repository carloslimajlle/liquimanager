package io.github.carloslimajlle.liquimanager.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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

/** The runner against a stub LiquiManager and a real (H2) database: claim, roll back, report, complete. */
class RunnerTest {

    private static final String CHANGELOG = """
            --liquibase formatted sql

            --changeset p.ricardo:SUP-1-001-create-t
            create table t (id int primary key);
            --rollback drop table t;

            --changeset p.ricardo:SUP-1-002-add-name
            alter table t add column name varchar(20);
            --rollback alter table t drop column name;
            """;

    @TempDir
    Path dir;

    private HttpServer server;
    private volatile String claimBody;
    /** Hand the job out once, like the real claim does: the next ask finds nothing. */
    private volatile boolean claimOnce;
    private final List<String> reports = new CopyOnWriteArrayList<>();
    private final List<String> completions = new CopyOnWriteArrayList<>();
    private final UUID parent = UUID.randomUUID();
    private String h2;

    @BeforeEach
    void start() throws IOException {
        // Credentials in the URL only because the stub has no secret to resolve; LiquiManager never sends them.
        h2 = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;USER=sa;PASSWORD=";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/runner/claim", exchange -> {
            String body = claimBody;
            if (body != null && claimOnce) {
                claimBody = null;
            }
            if (body == null) {
                exchange.sendResponseHeaders(204, -1);
            } else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.createContext("/api/v1/runner/requests/", exchange -> {
            completions.add(exchange.getRequestURI().getPath() + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
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

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private int lqm(String... args) {
        PrintStream out = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            return new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(out);
        }
    }

    private void applyChangelog(Path root) throws IOException {
        Files.createDirectories(root.resolve("db"));
        Files.writeString(root.resolve("db/master.sql"), CHANGELOG);
        assertThat(lqm("update", "--url", h2, "--changelog-file", "db/master.sql", "--workdir", root.toString(), "--report", "off"))
                .isZero();
    }

    private String job(String changesetId, String gitCommit) {
        return job(changesetId, gitCommit, "");
    }

    /** {@code plan}: what the request promised to undo, e.g. {@code , "plan": ["a::b::c"]}, or nothing. */
    private String job(String changesetId, String gitCommit, String plan) {
        return """
                { "id": "%s", "kind": "CHANGESET", "parentOperationId": "%s",
                  "changeset": { "id": "%s", "author": "p.ricardo", "filePath": "db/master.sql" },
                  "connection": { "key": "alvo", "jdbcUrl": "%s" }, "environment": "HML", "project": "gate",
                  "changelogPath": "db/master.sql"%s%s }
                """.formatted(UUID.randomUUID(), parent, changesetId, h2,
                gitCommit == null ? "" : ", \"gitCommit\": \"" + gitCommit + "\"", plan);
    }

    private int runner(String... extra) {
        List<String> args = new java.util.ArrayList<>(List.of("runner", "--once", "--lqm-url", url(), "--lqm-token", "lqm_x_y"));
        args.addAll(List.of(extra));
        return lqm(args.toArray(new String[0]));
    }

    private boolean tableExists() throws Exception {
        try (Connection connection = DriverManager.getConnection(h2.replace(";USER=sa;PASSWORD=", ""), "sa", "");
             Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from information_schema.tables where table_name = 'T'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    @Test
    @DisplayName("takes the approved rollback, reverts the changeset, reports it as answering the original, and says it is done")
    void carriesOutARollback() throws Exception {
        applyChangelog(dir);
        claimBody = job("SUP-1-001-create-t", null);

        int exit = runner("--workdir", dir.toString());

        assertThat(exit).isZero();
        assertThat(tableExists()).as("the changeset was reverted").isFalse();
        assertThat(reports).hasSize(1);
        assertThat(reports.get(0)).contains("\"command\":\"rollback-to-changeset\"", "\"outcome\":\"ROLLED_BACK\"",
                "\"parentOperationId\":\"" + parent + "\"", "\"source\":\"ui\"");
        String reported = reports.get(0).replaceAll(".*\"operationId\":\"([^\"]+)\".*", "$1");
        assertThat(completions).hasSize(1);
        assertThat(completions.get(0)).contains("\"ok\":true", "\"resultOperationId\":\"" + reported + "\"");
    }

    @Test
    @DisplayName("going back to a changeset undoes it and what came after, as the request promised")
    void undoesTheTail() throws Exception {
        applyChangelog(dir);
        claimBody = job("SUP-1-001-create-t", null, ", \"plan\": [\"SUP-1-002-add-name::p.ricardo::db/master.sql\", "
                + "\"SUP-1-001-create-t::p.ricardo::db/master.sql\"]");

        assertThat(runner("--workdir", dir.toString())).isZero();

        assertThat(tableExists()).as("both undone, newest first").isFalse();
        assertThat(reports.get(0)).contains("SUP-1-002-add-name", "SUP-1-001-create-t");
    }

    @Test
    @DisplayName("a database that no longer matches what was requested is left alone, and the request fails")
    void refusesWhenThePlanNoLongerMatches() throws Exception {
        applyChangelog(dir);
        // the request promised to undo only the first one; the database holds a second changeset on top of it
        claimBody = job("SUP-1-001-create-t", null, ", \"plan\": [\"SUP-1-001-create-t::p.ricardo::db/master.sql\"]");

        assertThat(runner("--workdir", dir.toString())).isEqualTo(1);

        assertThat(tableExists()).as("nothing was undone").isTrue();
        assertThat(completions.get(0)).contains("\"ok\":false");
        assertThat(reports.get(0)).contains("changed since the rollback was requested");
    }

    @Test
    @DisplayName("a rollback that cannot be done is reported as failed, with the operation that explains it")
    void reportsAFailure() throws Exception {
        applyChangelog(dir);
        claimBody = job("SUP-NOT-THERE", null);

        int exit = runner("--workdir", dir.toString());

        assertThat(exit).isEqualTo(1);
        assertThat(completions.get(0)).contains("\"ok\":false", "resultOperationId", "exited with code");
        assertThat(reports.get(0)).contains("\"status\":\"FAILURE\"", "not in the changelog");
        assertThat(tableExists()).as("nothing was touched").isTrue();
    }

    @Test
    @DisplayName("with nothing to do it says so and exits cleanly")
    void nothingToDo() {
        assertThat(runner()).isZero();
        assertThat(completions).isEmpty();
    }

    @Test
    @DisplayName("without a URL and a token it refuses to start")
    void needsConfiguration() {
        assertThat(lqm("runner", "--once")).isEqualTo(2);
    }

    @Test
    @DisplayName("a tag job runs the rollback to a tag")
    void tagJob() throws Exception {
        applyChangelog(dir);
        assertThat(lqm("tag", "v1", "--url", h2, "--changelog-file", "db/master.sql", "--workdir", dir.toString(), "--report", "off")).isZero();
        claimBody = """
                { "id": "%s", "kind": "TAG", "tag": "v1", "parentOperationId": "%s",
                  "connection": { "key": "alvo", "jdbcUrl": "%s" }, "environment": "HML", "project": "gate",
                  "changelogPath": "db/master.sql" }
                """.formatted(UUID.randomUUID(), parent, h2);

        int exit = runner("--workdir", dir.toString());

        assertThat(exit).isZero();
        assertThat(reports.get(0)).contains("\"command\":\"rollback\"", "\"tag\":\"v1\"");
        assertThat(completions.get(0)).contains("\"ok\":true");
    }

    @Test
    @DisplayName("clones the project at the commit of the original operation, not at what the branch says today")
    void usesTheOriginalCommit(@TempDir Path elsewhere) throws Exception {
        assumeTrue(gitAvailable(), "git is not installed");
        Path repo = elsewhere.resolve("repo");
        Files.createDirectories(repo.resolve("db"));
        Files.writeString(repo.resolve("db/master.sql"), CHANGELOG);
        git(repo, "init", "--quiet");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "add", ".");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "--quiet", "-m", "original");
        String original = git(repo, "rev-parse", "HEAD").strip();
        // The branch moves on: the changeset is gone from the head.
        Files.writeString(repo.resolve("db/master.sql"), "--liquibase formatted sql\n");
        git(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "--quiet", "-am", "later");

        applyChangelog(dir);
        claimBody = job("SUP-1-001-create-t", original);

        int exit = runner("--git-url", repo.toUri().toString());

        assertThat(exit).as(String.join("\n", reports)).isZero();
        assertThat(tableExists()).isFalse();
    }

    private static boolean gitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String git(Path in, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(in.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    // ------------------------------------------------------------------ as a service

    private RunnerCommand service(int pollSeconds) {
        RunnerCommand command = new RunnerCommand();
        command.lqmUrl = url();
        command.lqmToken = "lqm_x_y";
        command.pollSeconds = pollSeconds;
        command.workdir = dir.toString();
        return command;
    }

    private static io.github.carloslimajlle.liquimanager.client.LqmClient clientTo(String url) {
        return new io.github.carloslimajlle.liquimanager.client.LqmClient(new io.github.carloslimajlle.liquimanager.client.LqmConfig(
                url, "lqm_x_y", java.time.Duration.ofSeconds(5), null));
    }

    @Test
    @DisplayName("without --once it keeps asking for work, and leaves at once when told to stop, not after the poll interval")
    void staysUpAndStopsPromptly() throws Exception {
        java.util.concurrent.CountDownLatch stop = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Integer> running = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> service(60).run(clientTo(url()), stop));

        Thread.sleep(400);
        assertThat(running).as("a service does not return by itself").isNotDone();
        long asked = System.nanoTime();
        stop.countDown();

        assertThat(running.get(5, java.util.concurrent.TimeUnit.SECONDS)).isZero();
        assertThat((System.nanoTime() - asked) / 1_000_000).as("it did not sit out the 60 s wait").isLessThan(3_000);
    }

    @Test
    @DisplayName("as a service it takes a rollback, goes straight back to asking, and carries on until stopped")
    void worksThenKeepsWatching() throws Exception {
        applyChangelog(dir);
        claimBody = job("SUP-1-001-create-t", null);
        claimOnce = true;
        java.util.concurrent.CountDownLatch stop = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CompletableFuture<Integer> running = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> service(60).run(clientTo(url()), stop));

        long deadline = System.currentTimeMillis() + 15_000;
        while (completions.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(completions).hasSize(1);
        assertThat(running).as("still watching after the job").isNotDone();
        assertThat(tableExists()).as("the changeset was reverted").isFalse();

        stop.countDown();
        assertThat(running.get(5, java.util.concurrent.TimeUnit.SECONDS)).isZero();
        assertThat(completions).as("it did not take the same job twice").hasSize(1);
    }
}
