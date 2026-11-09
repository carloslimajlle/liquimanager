package io.github.carloslimajlle.liquimanager.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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

import com.sun.net.httpserver.HttpServer;

import picocli.CommandLine;

/** The commands end to end: real Liquibase on H2, a stub LiquiManager, real exit codes. */
class CliTest {

    private static final String SAFE = """
            --liquibase formatted sql

            --changeset p.ricardo:SUP-1-001-create-t
            create table t (id int primary key, legacy int);
            --rollback drop table t;
            """;

    private static final String DROP = SAFE + """

            --changeset p.ricardo:SUP-1-002-drop-col
            alter table t drop column legacy;
            """;

    @TempDir
    Path dir;

    private HttpServer server;
    private final List<String> reports = new CopyOnWriteArrayList<>();
    private String effectiveJson;
    /** What the stub answers to the order gate; null means the answer is a 404 (LiquiManager cannot say). */
    private volatile String gateJson;
    private String changelogName = "master.sql";
    private boolean dbCredentials = true;

    @BeforeEach
    void startServer() throws IOException {
        // Severity 4 against a threshold of 4: DropColumnWarn blocks.
        effectiveJson = "{\"package\":\"default\",\"etag\":\"e\",\"blockThreshold\":4,\"checks\":["
                + "{\"code\":\"DropColumnWarn\",\"kind\":\"BUILTIN\",\"severity\":4,\"parameters\":{}}],\"exceptions\":[]}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/policies/effective", exchange -> {
            byte[] body = effectiveJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/policies/promotion-gate", exchange -> {
            if (gateJson == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                byte[] body = gateJson.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
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

    private String lqmUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private int run(String changelog, String... args) throws IOException {
        return runOn("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", changelog, args);
    }

    private int runOn(String h2, String changelog, String... args) throws IOException {
        Path file = dir.resolve(changelogName);
        Files.writeString(file, changelog);
        String[] base = dbCredentials
                ? new String[] {
                "--url", h2, "--username", "sa", "--password", "", "--changelog-file", file.toString(),
                "--project", "gate", "--env", "DEV", "--lqm-url", lqmUrl(), "--lqm-token", "lqm_x_y",
                "--buffer-dir", dir.resolve("buffer").toString()}
                : new String[] {
                "--url", h2, "--changelog-file", file.toString(),
                "--project", "gate", "--env", "DEV", "--lqm-url", lqmUrl(), "--lqm-token", "lqm_x_y",
                "--buffer-dir", dir.resolve("buffer").toString()};
        // words first (the command path), then the common options, then the test's own flags
        int words = 0;
        while (words < args.length && !args[words].startsWith("--")) {
            words++;
        }
        List<String> line = new java.util.ArrayList<>(List.of(args).subList(0, words));
        line.addAll(List.of(base));
        line.addAll(List.of(args).subList(words, args.length));
        String[] all = line.toArray(new String[0]);
        PrintStream original = System.out;
        System.setOut(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        try {
            return new CommandLine(new Main()).execute(all);
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void updateAppliesReportsAndExitsZero() throws IOException {
        assertThat(run(SAFE, "update")).isZero();

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0)).contains("\"command\":\"update\"", "\"status\":\"SUCCESS\"",
                "SUP-1-001-create-t", "\"outcome\":\"EXECUTED\"", "\"source\":\"cli-local\"");
        assertThat(reports.get(0)).doesNotContain("password");
    }

    private static String gateWith(String checksum) {
        return "{\"gated\":true,\"previousEnv\":\"DEV\",\"executed\":[{\"id\":\"SUP-1-001-create-t\",\"author\":\"p.ricardo\","
                + "\"filePath\":\"master.sql\",\"checksum\":\"" + checksum + "\"}]}";
    }

    @Test
    void aChangesetThePreviousStageRanInAnotherVersionIsHeldBack() throws IOException {
        gateJson = gateWith("9:oldversion");

        assertThat(run(SAFE, "update")).isEqualTo(3);

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0)).contains("\"status\":\"BLOCKED\"").doesNotContain("\"outcome\":\"EXECUTED\"");
    }

    @Test
    void validCheckSumForWhatThePreviousStageRanLetsItThrough() throws IOException {
        changelogName = "master.yaml";
        gateJson = gateWith("9:oldversion").replace("master.sql", "master.yaml");
        String yaml = """
                databaseChangeLog:
                  - changeSet:
                      id: SUP-1-001-create-t
                      author: p.ricardo
                      validCheckSum: %s
                      changes:
                        - createTable:
                            tableName: t
                            columns:
                              - column: { name: id, type: INT }
                      rollback:
                        - dropTable: { tableName: t }
                """;

        assertThat(run(yaml.formatted("9:oldversion"), "update")).as("declared as valid: the same version").isZero();
        assertThat(reports.get(0)).contains("\"status\":\"SUCCESS\"", "\"outcome\":\"EXECUTED\"");

        reports.clear();
        assertThat(run(yaml.formatted("9:somethingelse"), "update")).as("another checksum declared: still not the version DEV ran").isEqualTo(3);
    }

    @Test
    void updateCountRunsOnlyTheFirstAndTheGateJudgesOnlyThose() throws IOException {
        assertThat(run(DROP, "update")).as("everything pending: the drop is in it").isEqualTo(3);

        reports.clear();
        assertThat(run(DROP, "update-count", "--count", "1")).as("only the first: the drop is not judged").isZero();

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0)).contains("\"command\":\"update-count\"", "\"status\":\"SUCCESS\"", "SUP-1-001-create-t");
        assertThat(reports.get(0)).as("the second one did not run").doesNotContain("\"outcome\":\"FAILED\"");
        assertThat(reports.get(0)).contains("\"outcome\":\"FILTERED\"");
    }

    @Test
    void updateCountNeedsAPositiveCount() throws IOException {
        assertThat(run(SAFE, "update-count", "--count", "0")).isEqualTo(2);
    }

    private static final String TAGGED = """
            databaseChangeLog:
              - changeSet:
                  id: SUP-1-001-create-t
                  author: p.ricardo
                  changes:
                    - createTable:
                        tableName: t
                        columns:
                          - column: { name: id, type: INT }
                          - column: { name: legacy, type: INT }
                  rollback:
                    - dropTable: { tableName: t }
              - changeSet:
                  id: tag-v1
                  author: p.ricardo
                  changes:
                    - tagDatabase: { tag: v1 }
              - changeSet:
                  id: SUP-1-002-drop-col
                  author: p.ricardo
                  changes:
                    - dropColumn: { tableName: t, columnName: legacy }
            """;

    @Test
    void updateToTagStopsAtTheTagAndTheGateJudgesOnlyUpToIt() throws IOException {
        changelogName = "master.yaml";

        assertThat(run(TAGGED, "update-to-tag", "--tag", "v1")).as("the drop comes after the tag").isZero();

        assertThat(reports.get(0)).contains("\"command\":\"update-to-tag\"", "SUP-1-001-create-t", "tag-v1", "\"outcome\":\"FILTERED\"");
    }

    @Test
    void aTagTheChangelogDoesNotHaveIsAFailureNotAQuietNoOp() throws IOException {
        changelogName = "master.yaml";

        assertThat(run(TAGGED, "update-to-tag", "--tag", "nope")).isEqualTo(1);
    }

    @Test
    void rollbackCountRevertsTheLastApplied() throws IOException {
        String h2 = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        assertThat(runOn(h2, SAFE, "update")).isZero();
        reports.clear();

        assertThat(runOn(h2, SAFE, "rollback-count", "--count", "1")).isZero();

        assertThat(reports.get(0)).contains("\"command\":\"rollback-count\"", "\"status\":\"SUCCESS\"");
    }

    @Test
    void aSecretRefThatCannotBeResolvedIsAFailureWithAMessageThatNamesNoValue() throws IOException {
        // No VAULT_ADDR in the test environment: the job cannot reach the provider, and says so.
        dbCredentials = false;

        assertThat(run(SAFE, "update", "--secret-ref", "vault:secret/data/lqm/db")).isEqualTo(1);

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0)).contains("\"status\":\"FAILURE\"", "VAULT_ADDR is not set");
    }

    @Test
    void usernameOrPasswordGivenMeansTheSecretRefIsNotAsked() throws IOException {
        assertThat(run(SAFE, "update", "--secret-ref", "vault:secret/data/lqm/db")).as("--username sa is already in the base options").isZero();
    }

    private static final String REFERENCE = """
            --liquibase formatted sql

            --changeset p.ricardo:REF-1
            create table t (id int primary key, extra varchar(10));
            --rollback drop table t;
            """;

    private String h2() {
        return "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    }

    @Test
    void diffSaysWhatDiffersAndFailsOnDemand() throws IOException {
        String target = h2();
        String reference = h2();
        assertThat(runOn(target, SAFE, "update")).isZero();
        assertThat(runOn(reference, REFERENCE, "update")).isZero();
        reports.clear();

        assertThat(runOn(target, "", "diff", "--reference-url", reference, "--reference-username", "sa")).isZero();
        assertThat(reports.get(0)).contains("\"command\":\"diff\"", "\"status\":\"SUCCESS\"", "missing", "unexpected");
        assertThat(reports.get(0)).as("a read-only command moves no changeset").doesNotContain("\"outcome\":\"EXECUTED\"");

        assertThat(runOn(target, "", "diff", "--reference-url", reference, "--reference-username", "sa", "--fail-on-diff")).isEqualTo(1);
        assertThat(runOn(target, "", "diff", "--reference-url", target, "--reference-username", "sa", "--fail-on-diff"))
                .as("the same schema: nothing differs").isZero();
    }

    @Test
    void historyListsWhatWasApplied() throws IOException {
        String target = h2();
        assertThat(runOn(target, SAFE, "update")).isZero();
        reports.clear();

        assertThat(runOn(target, "", "history")).isZero();

        assertThat(reports.get(0)).contains("\"command\":\"history\"", "SUP-1-001-create-t");
    }

    @Test
    void snapshotWritesTheSchemaToAFile() throws IOException {
        String target = h2();
        assertThat(runOn(target, SAFE, "update")).isZero();
        Path out = dir.resolve("snap").resolve("schema.json");

        assertThat(runOn(target, "", "snapshot", "--snapshot-out", out.toString())).isZero();

        assertThat(out).exists();
        assertThat(Files.readString(out)).containsIgnoringCase("legacy");
        assertThat(runOn(target, "", "snapshot", "--snapshot-format", "nonsense")).as("an unknown format is a failure").isEqualTo(1);
    }

    @Test
    void diffChangelogWritesTheChangesThatTurnTheTargetIntoTheReference() throws IOException {
        String target = h2();
        String reference = h2();
        assertThat(runOn(target, SAFE, "update")).isZero();
        assertThat(runOn(reference, REFERENCE, "update")).isZero();
        Path out = dir.resolve("out").resolve("diff.xml");

        assertThat(runOn(target, "", "diff-changelog", "--reference-url", reference, "--reference-username", "sa",
                "--changelog-out", out.toString())).isZero();

        assertThat(out).exists();
        assertThat(Files.readString(out)).containsIgnoringCase("addColumn").containsIgnoringCase("extra");
    }

    @Test
    void aBlockingFindingStopsTheRunWithExitThree() throws IOException {
        assertThat(run(DROP, "update")).isEqualTo(3);

        assertThat(reports).hasSize(1);
        assertThat(reports.get(0)).contains("\"status\":\"BLOCKED\"", "DropColumnWarn", "\"result\":\"BLOCKED\"");
        // Nothing ran: the blocked changeset must not appear as executed.
        assertThat(reports.get(0)).doesNotContain("\"outcome\":\"EXECUTED\"");
    }

    @Test
    void checksOffRunsEvenWhatThePolicyWouldBlock() throws IOException {
        assertThat(run(DROP, "update", "--checks", "off")).isZero();

        assertThat(reports.get(0)).contains("\"status\":\"SUCCESS\"").doesNotContain("\"gateResult\"");
    }

    @Test
    void checksRunReportsTheVerdictAndChangesNothing() throws IOException {
        assertThat(run(DROP, "checks", "run")).isEqualTo(3);
        assertThat(reports.get(0)).contains("\"command\":\"checks run\"");
    }

    @Test
    void aLiquiManagerThatIsDownNeverStopsTheDeploy() throws IOException {
        int port = server.getAddress().getPort();
        server.stop(0);

        assertThat(run(SAFE, "update")).isZero();
        assertThat(port).isPositive();
        assertThat(Files.list(dir.resolve("buffer")).count()).isEqualTo(1);
    }

    @Test
    void rollbackOneChangesetRevertsItAndAnswersTheOperationThatAskedForIt() throws IOException {
        String h2 = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        String parent = UUID.randomUUID().toString();
        assertThat(runOn(h2, SAFE, "update")).isZero();
        reports.clear();

        assertThat(runOn(h2, SAFE, "rollback-one-changeset", "--changeset-id", "SUP-1-001-create-t",
                "--parent-operation", parent)).isZero();

        assertThat(reports.get(0)).contains("\"command\":\"rollback-one-changeset\"", "\"outcome\":\"ROLLED_BACK\"",
                "\"parentOperationId\":\"" + parent + "\"");
    }

    @Test
    void rollbackOneChangesetOfSomethingNeverAppliedFailsAndSaysWhy() throws IOException {
        assertThat(run(SAFE, "rollback-one-changeset", "--changeset-id", "SUP-1-001-create-t")).isEqualTo(1);

        assertThat(reports.get(0)).contains("\"status\":\"FAILURE\"", "never applied");
    }

    @Test
    void statusIsReadOnlyAndSaysWhatIsPending() throws IOException {
        assertThat(run(SAFE, "status")).isZero();

        assertThat(reports.get(0)).contains("\"command\":\"status\"").doesNotContain("\"outcome\":\"EXECUTED\"");
    }

    @Test
    void missingTheDatabaseUrlIsAUsageError() {
        PrintStream err = System.err;
        System.setErr(new PrintStream(new ByteArrayOutputStream()));
        try {
            assertThat(new CommandLine(new Main()).execute("update")).isEqualTo(2);
        } finally {
            System.setErr(err);
        }
    }
}
