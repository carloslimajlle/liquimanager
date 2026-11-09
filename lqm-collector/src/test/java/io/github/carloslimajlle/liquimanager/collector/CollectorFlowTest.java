package io.github.carloslimajlle.liquimanager.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.client.EffectivePolicies;
import io.github.carloslimajlle.liquimanager.client.IngestPayload;
import io.github.carloslimajlle.liquimanager.policy.GateResult;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/** The recorder, the gate and the report builder against a real Liquibase run on H2. */
class CollectorFlowTest {

    private Liquibase open(String changelog) throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        java.sql.Connection connection = java.sql.DriverManager.getConnection(url, "sa", "");
        Database database = DatabaseFactory.getInstance()
                .findCorrectDatabaseImplementation(new JdbcConnection(connection));
        return new Liquibase(changelog, new ClassLoaderResourceAccessor(), database);
    }

    private static Map<String, String> outcomes(ChangeSetRecorder recorder) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        recorder.changesets().forEach(c -> out.put(c.id(), c.outcome()));
        return out;
    }

    @Test
    @SuppressWarnings("resource")
    void recordsExecutedAndFilteredChangesetsWithTheReason() throws Exception {
        Liquibase lb = open("db/changelog.xml");
        ChangeSetRecorder recorder = new ChangeSetRecorder();
        lb.setChangeExecListener(recorder);
        Contexts app = new Contexts("app");
        LabelExpression none = new LabelExpression();

        recorder.classify(lb, app, none);
        lb.update(app, none);

        assertThat(outcomes(recorder)).containsExactly(
                Map.entry("SUP-1-001-create-cliente", "EXECUTED"),
                Map.entry("SUP-1-002-seed-only-dev", "FILTERED"),
                Map.entry("SUP-1-003-raw-no-rollback", "EXECUTED"));
        assertThat(recorder.changesets().get(1).filterReason()).isEqualTo("context");
        assertThat(recorder.changesets().get(0).checksum()).isNotBlank();
        assertThat(recorder.changesets().get(0).hasRollback()).isTrue();
        assertThat(recorder.changesets().get(2).hasRollback()).isFalse();
        assertThat(recorder.summary()).isEqualTo(new IngestPayload.Summary(2, 0, 1, 0));
        assertThat(recorder.anyFailed()).isFalse();
    }

    @Test
    @SuppressWarnings("resource")
    void aSecondRunSeesTheEarlierOnesAsPreviouslyRun() throws Exception {
        Liquibase lb = open("db/changelog.xml");
        Contexts app = new Contexts("app");
        lb.update(app, new LabelExpression());

        ChangeSetRecorder recorder = new ChangeSetRecorder();
        lb.setChangeExecListener(recorder);
        recorder.classify(lb, app, new LabelExpression());
        lb.update(app, new LabelExpression());

        assertThat(outcomes(recorder)).containsEntry("SUP-1-001-create-cliente", "PREVIOUSLY_RUN");
        assertThat(recorder.summary().executed()).isZero();
    }

    @Test
    @SuppressWarnings("resource")
    void aFailureIsRecordedAndWhatCameAfterItStaysNotExecuted() throws Exception {
        Liquibase lb = open("db/broken.xml");
        ChangeSetRecorder recorder = new ChangeSetRecorder();
        lb.setChangeExecListener(recorder);
        recorder.classify(lb, new Contexts(), new LabelExpression());

        try {
            lb.update(new Contexts(), new LabelExpression());
        } catch (Exception expected) {
            // the run is meant to fail; the point is what the recorder saw
        }

        assertThat(outcomes(recorder)).containsExactly(
                Map.entry("OK-1", "EXECUTED"),
                Map.entry("BAD-2", "FAILED"),
                Map.entry("NEVER-3", "NOT_EXECUTED"));
        assertThat(recorder.anyFailed()).isTrue();
        assertThat(recorder.changesets().get(1).error()).isNotBlank();
    }

    private static EffectivePolicies policies(int threshold, int rollbackSeverity) {
        return new EffectivePolicies("default", "etag", threshold,
                List.of(new EffectivePolicies.Check("RollbackRequired", "BUILTIN", rollbackSeverity, Map.of(), null, null, null)),
                List.of());
    }

    @Test
    @SuppressWarnings("resource")
    void theGateBlocksWhenAFindingReachesTheThreshold() throws Exception {
        Liquibase lb = open("db/changelog.xml");

        Optional<PolicyGate.Verdict> verdict = PolicyGate.evaluate(
                lb, new Contexts("app"), new LabelExpression(), policies(4, 4), "PRD", Instant.now());

        assertThat(verdict).isPresent();
        assertThat(verdict.get().blocked()).isTrue();
        assertThat(verdict.get().findings()).extracting("checkCode").containsOnly("RollbackRequired");
        assertThat(verdict.get().payload().findings()).extracting(IngestPayload.Finding::result)
                .containsOnly("BLOCKED");
    }

    @Test
    @SuppressWarnings("resource")
    void theSameFindingOnlyWarnsBelowTheThreshold() throws Exception {
        Liquibase lb = open("db/changelog.xml");

        PolicyGate.Verdict verdict = PolicyGate.evaluate(
                lb, new Contexts("app"), new LabelExpression(), policies(4, 2), "DEV", Instant.now()).orElseThrow();

        assertThat(verdict.gate()).isEqualTo(GateResult.WARNED);
        assertThat(verdict.blocked()).isFalse();
    }

    @Test
    @SuppressWarnings("resource")
    void theReportCarriesNoCredentialsAndDescribesTheRun() throws Exception {
        Liquibase lb = open("db/changelog.xml");
        ChangeSetRecorder recorder = new ChangeSetRecorder();
        lb.setChangeExecListener(recorder);
        lb.update(new Contexts("app"), new LabelExpression());

        RunInfo info = new RunInfo("update", "cli-local", "gate", "DEV", null, "db/changelog.xml",
                "app", null, null, null, "cli", "test", null, null);
        IngestPayload payload = new ReportBuilder(info).build(
                "SUCCESS", lb.getDatabase(), "jdbc:h2:mem:x;user=sa;password=pw", recorder, Optional.empty(),
                null, null, "log", null);

        assertThat(payload.connection().jdbcUrl()).isEqualTo("jdbc:h2:mem:x");
        assertThat(payload.changelog().format()).isEqualTo("XML");
        assertThat(payload.runtime().liquibaseVersion()).isNotBlank();
        assertThat(payload.changesets()).isNotEmpty();
        assertThat(payload.error()).isNull();
    }

    @Test
    @SuppressWarnings("resource")
    void theReportSaysWhetherItListsTheWholeChangelog() throws Exception {
        RunInfo info = new RunInfo("update", "cli-local", "gate", "DEV", null, "db/changelog.xml",
                "app", null, null, null, "cli", "test", null, null);

        // classified before the command: every changeset of the changelog is in the report, so the server can tell which ones are gone
        Liquibase classified = open("db/changelog.xml");
        ChangeSetRecorder whole = new ChangeSetRecorder();
        classified.setChangeExecListener(whole);
        whole.classify(classified, new Contexts("app"), new LabelExpression());
        IngestPayload complete = new ReportBuilder(info).build("SUCCESS", classified.getDatabase(), "jdbc:h2:mem:x", whole,
                Optional.empty(), null, null, "log", null);
        assertThat(complete.changelog().complete()).isTrue();
        assertThat(complete.changesets()).hasSize(3);

        // never classified (only what ran is known): the report must not claim to be the whole changelog
        ChangeSetRecorder partial = new ChangeSetRecorder();
        IngestPayload incomplete = new ReportBuilder(info).build("SUCCESS", classified.getDatabase(), "jdbc:h2:mem:x", partial,
                Optional.empty(), null, null, "log", null);
        assertThat(incomplete.changelog().complete()).isFalse();
    }

    @Test
    void aGitLabMergeRequestPipelineNamesTheMergeRequest() {
        IngestPayload.Ci ci = RunInfo.detectCi(Map.of("GITLAB_CI", "true", "CI_PIPELINE_ID", "105", "CI_JOB_URL", "http://git/job/1",
                "CI_MERGE_REQUEST_IID", "18", "CI_MERGE_REQUEST_TITLE", "Renomeia o arquivo do changeset 004",
                "CI_MERGE_REQUEST_PROJECT_URL", "http://git/root/poc"));
        assertThat(ci.mergeRequestIid()).isEqualTo("18");
        assertThat(ci.mergeRequestTitle()).isEqualTo("Renomeia o arquivo do changeset 004");
        assertThat(ci.mergeRequestUrl()).isEqualTo("http://git/root/poc/-/merge_requests/18");

        IngestPayload.Ci branchPipeline = RunInfo.detectCi(Map.of("GITLAB_CI", "true", "CI_PIPELINE_ID", "106"));
        assertThat(branchPipeline.mergeRequestIid()).isNull();
        assertThat(branchPipeline.mergeRequestUrl()).isNull();
    }
}
