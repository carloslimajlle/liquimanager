package io.github.carloslimajlle.liquimanager.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.policy.builtin.ChangesetCommentRequired;
import io.github.carloslimajlle.liquimanager.policy.builtin.ChangesetIdPattern;
import io.github.carloslimajlle.liquimanager.policy.builtin.ContextRequired;
import io.github.carloslimajlle.liquimanager.policy.builtin.DeleteWithoutWhere;
import io.github.carloslimajlle.liquimanager.policy.builtin.DropTableWarn;
import io.github.carloslimajlle.liquimanager.policy.builtin.GrantToPublicForbidden;
import io.github.carloslimajlle.liquimanager.policy.builtin.OneChangePerChangeset;
import io.github.carloslimajlle.liquimanager.policy.builtin.RollbackRequired;
import io.github.carloslimajlle.liquimanager.policy.builtin.SecretInChangelog;
import io.github.carloslimajlle.liquimanager.policy.builtin.TruncateTableWarn;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.core.PostgresDatabase;

/**
 * Policy engine tests.
 *
 * <p>They cover what the spec promises and what would be expensive to discover in
 * production: the gate rule, the effect of exceptions, and the behaviour of every
 * implemented check. All in memory — no database, no network.
 */
class PolicyEngineTest {

    private static final PostgresDatabase DATABASE = new PostgresDatabase();
    private static final DatabaseChangeLog CHANGELOG = new DatabaseChangeLog("db.changelog-master.xml");

    // ----------------------------------------------------------------- helpers

    private static ChangeSet changeSet(String id, String contextFilter) {
        return new ChangeSet(id, "p.ricardo", false, false,
                "db/changelog/work/SUP-3512/002-add-uk.xml", contextFilter, null, CHANGELOG);
    }

    /** A changeset Liquibase knows how to undo on its own (createTable). */
    private static ChangeSet autoReversible(String id) {
        ChangeSet cs = changeSet(id, "prd");
        CreateTableChange create = new CreateTableChange();
        create.setTableName("veiculo");
        cs.addChange(create);
        cs.setComments("cria a tabela de veiculos");
        return cs;
    }

    /** A raw SQL changeset: Liquibase cannot derive a rollback for it. */
    private static ChangeSet rawSql(String id, String sql) {
        ChangeSet cs = changeSet(id, "prd");
        RawSQLChange raw = new RawSQLChange();
        raw.setSql(sql);
        cs.addChange(raw);
        cs.setComments("sql cru");
        return cs;
    }

    private static CheckContext context(List<ChangeSet> changeSets, String generatedSql) {
        return new CheckContext(
                CHANGELOG, changeSets, DATABASE,
                cs -> generatedSql == null ? List.of() : List.of(generatedSql),
                Optional.empty(), "PRD", true);
    }

    private static CheckContext context(ChangeSet... changeSets) {
        return context(List.of(changeSets), null);
    }

    private static List<Finding> run(PolicyCheck check, CheckContext ctx) {
        return check.evaluate(ctx, Map.of());
    }

    // ------------------------------------------------------------- the checks

    @Nested
    @DisplayName("RollbackRequired")
    class RollbackRequiredTest {

        @Test
        @DisplayName("accepts an auto-reversible change without demanding an explicit block")
        void acceptsAutoReversible() {
            assertThat(run(new RollbackRequired(), context(autoReversible("SUP-3512-001")))).isEmpty();
        }

        @Test
        @DisplayName("reports raw SQL with no declared rollback")
        void reportsRawSqlWithoutRollback() {
            List<Finding> findings = run(new RollbackRequired(),
                    context(rawSql("SUP-3512-002", "create index ix_a on t(a)")));

            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).checkCode()).isEqualTo("RollbackRequired");
            assertThat(findings.get(0).changesetKey()).startsWith("SUP-3512-002::p.ricardo::");
        }

        @Test
        @DisplayName("accepts raw SQL when an explicit rollback is present")
        void acceptsExplicitRollback() {
            ChangeSet cs = rawSql("SUP-3512-003", "create index ix_a on t(a)");
            RawSQLChange undo = new RawSQLChange();
            undo.setSql("drop index ix_a");
            cs.addRollbackChange(undo);

            assertThat(run(new RollbackRequired(), context(cs))).isEmpty();
        }
    }

    @Nested
    @DisplayName("ContextRequired")
    class ContextRequiredTest {

        @Test
        @DisplayName("reports a changeset with no context, which would also run in PRD")
        void reportsMissingContext() {
            ChangeSet cs = changeSet("SUP-3512-004", null);
            assertThat(run(new ContextRequired(), context(cs)))
                    .singleElement()
                    .extracting(Finding::message, InstanceOfAssertFactories.STRING)
                    .contains("PRD");
        }

        @Test
        @DisplayName("accepts a changeset that declares a context")
        void acceptsDeclaredContext() {
            assertThat(run(new ContextRequired(), context(changeSet("SUP-3512-005", "dev,tst,hml,prd"))))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("ChangesetIdPattern")
    class ChangesetIdPatternTest {

        @Test
        @DisplayName("accepts the SUP-NNNN-NNN pattern from D-16")
        void acceptsDefaultPattern() {
            assertThat(run(new ChangesetIdPattern(), context(changeSet("SUP-3512-002-add-uk-placa", "prd"))))
                    .isEmpty();
        }

        @Test
        @DisplayName("reports an id outside the pattern")
        void reportsOutsidePattern() {
            assertThat(run(new ChangesetIdPattern(), context(changeSet("corrige-tabela", "prd")))).hasSize(1);
        }

        @Test
        @DisplayName("honours the pattern parameter coming from the package")
        void honoursParameter() {
            CheckContext ctx = context(changeSet("TASK-99", "prd"));
            assertThat(new ChangesetIdPattern().evaluate(ctx, Map.of("pattern", "^TASK-[0-9]+$"))).isEmpty();
        }

        @Test
        @DisplayName("an invalid regex does not break the evaluation")
        void invalidRegexStaysQuiet() {
            CheckContext ctx = context(changeSet("qualquer", "prd"));
            assertThat(new ChangesetIdPattern().evaluate(ctx, Map.of("pattern", "[unclosed"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Checks over the generated SQL")
    class GeneratedSqlTest {

        @Test
        @DisplayName("DropTableWarn catches DROP TABLE wherever it came from")
        void catchesDropTable() {
            CheckContext ctx = context(List.of(rawSql("SUP-1", "x")), "DROP TABLE dbo.veiculo;");
            assertThat(run(new DropTableWarn(), ctx)).hasSize(1);
        }

        @Test
        @DisplayName("TruncateTableWarn catches TRUNCATE")
        void catchesTruncate() {
            CheckContext ctx = context(List.of(rawSql("SUP-1", "x")), "truncate table agendamento;");
            assertThat(run(new TruncateTableWarn(), ctx)).hasSize(1);
        }

        @Test
        @DisplayName("DeleteWithoutWhere reports DELETE without WHERE and spares the one with it")
        void deleteWithoutWhere() {
            var check = new DeleteWithoutWhere();

            assertThat(run(check, context(List.of(rawSql("SUP-1", "x")), "delete from veiculo;")))
                    .as("missing WHERE should be reported").hasSize(1);

            assertThat(run(check, context(List.of(rawSql("SUP-2", "x")), "delete from veiculo where id = 1;")))
                    .as("WHERE present, nothing to report").isEmpty();
        }

        @Test
        @DisplayName("GrantToPublicForbidden catches GRANT ... TO PUBLIC")
        void catchesGrantToPublic() {
            CheckContext ctx = context(List.of(rawSql("SUP-1", "x")), "GRANT SELECT ON dbo.veiculo TO PUBLIC;");
            assertThat(run(new GrantToPublicForbidden(), ctx)).hasSize(1);
        }

        @Test
        @DisplayName("SecretInChangelog catches a credential in plain text")
        void catchesPlainTextSecret() {
            CheckContext ctx = context(List.of(rawSql("SUP-1", "x")),
                    "create user app with password = 'Troca123!';");
            assertThat(run(new SecretInChangelog(), ctx)).hasSize(1);
        }

        @Test
        @DisplayName("with no generated SQL, the SQL checks report nothing")
        void noSqlNoFindings() {
            CheckContext ctx = context(rawSql("SUP-1", "x"));
            assertThat(run(new DropTableWarn(), ctx)).isEmpty();
        }
    }

    @Nested
    @DisplayName("OneChangePerChangeset")
    class OneChangePerChangesetTest {

        @Test
        @DisplayName("reports above the limit and honours the max parameter")
        void honoursMax() {
            ChangeSet cs = autoReversible("SUP-3512-006");
            CreateTableChange another = new CreateTableChange();
            another.setTableName("agendamento");
            cs.addChange(another);

            var check = new OneChangePerChangeset();
            assertThat(check.evaluate(context(cs), Map.of())).hasSize(1);
            assertThat(check.evaluate(context(cs), Map.of("max", 2))).isEmpty();
        }
    }

    // ------------------------------------------------------------- gate rule

    @Nested
    @DisplayName("Gate")
    class GateTest {

        private final PolicyEngine engine = PolicyEngine.withBuiltins();

        private EvaluationResult evaluate(CheckContext ctx, EffectivePackage pkg, List<ActiveException> ex) {
            return engine.evaluate(ctx, pkg, ex, Instant.parse("2026-09-15T19:07:03Z"));
        }

        @Test
        @DisplayName("no findings means PASSED")
        void passed() {
            var pkg = EffectivePackage.withDefaultThreshold("default", true,
                    List.of(EffectiveCheck.of("RollbackRequired", Severity.MAJOR)));

            var result = evaluate(context(autoReversible("SUP-3512-007")), pkg, List.of());

            assertThat(result.gate()).isEqualTo(GateResult.PASSED);
            assertThat(result.findings()).isEmpty();
        }

        @Test
        @DisplayName("a MAJOR finding below the threshold warns instead of blocking")
        void warned() {
            var pkg = EffectivePackage.withDefaultThreshold("default", true,
                    List.of(EffectiveCheck.of("RollbackRequired", Severity.MAJOR)));

            var result = evaluate(context(rawSql("SUP-3512-008", "create index ix on t(a)")), pkg, List.of());

            assertThat(result.gate()).isEqualTo(GateResult.WARNED);
            assertThat(result.worstSeverity()).isEqualTo(Severity.MAJOR);
        }

        @Test
        @DisplayName("a finding at the package threshold blocks")
        void blocked() {
            var pkg = EffectivePackage.withDefaultThreshold("default", true,
                    List.of(EffectiveCheck.of("RollbackRequired", Severity.CRITICAL)));

            var result = evaluate(context(rawSql("SUP-3512-009", "create index ix on t(a)")), pkg, List.of());

            assertThat(result.gate()).isEqualTo(GateResult.BLOCKED);
            assertThat(result.isBlocked()).isTrue();
        }

        @Test
        @DisplayName("the same finding does not block outside production: the threshold is higher")
        void higherThresholdOutsideProduction() {
            var inProd = EffectivePackage.withDefaultThreshold("default", true,
                    List.of(EffectiveCheck.of("RollbackRequired", Severity.CRITICAL)));
            var outsideProd = EffectivePackage.withDefaultThreshold("default", false,
                    List.of(EffectiveCheck.of("RollbackRequired", Severity.CRITICAL)));

            var ctx = context(rawSql("SUP-3512-010", "create index ix on t(a)"));

            assertThat(evaluate(ctx, inProd, List.of()).gate()).isEqualTo(GateResult.BLOCKED);
            assertThat(evaluate(ctx, outsideProd, List.of()).gate()).isEqualTo(GateResult.WARNED);
        }

        @Test
        @DisplayName("the package severity wins over the check default")
        void packageSeverityWins() {
            var pkg = EffectivePackage.withDefaultThreshold("default", true,
                    List.of(EffectiveCheck.of("ChangesetCommentRequired", Severity.BLOCKER)));

            ChangeSet withoutComment = changeSet("SUP-3512-011", "prd");
            var result = evaluate(context(withoutComment), pkg, List.of());

            // The check returns INFO by default; the package raised it to BLOCKER.
            assertThat(result.findings()).singleElement()
                    .extracting(Finding::severity).isEqualTo(Severity.BLOCKER);
            assertThat(result.gate()).isEqualTo(GateResult.BLOCKED);
        }

        @Test
        @DisplayName("a package entry with no implementation is skipped, not fatal")
        void unimplementedCheckIsSkipped() {
            var pkg = EffectivePackage.withDefaultThreshold("default", true, List.of(
                    EffectiveCheck.of("CheckThatDoesNotExistYet", Severity.BLOCKER),
                    EffectiveCheck.of("RollbackRequired", Severity.MAJOR)));

            var result = evaluate(context(autoReversible("SUP-3512-012")), pkg, List.of());

            assertThat(result.gate()).isEqualTo(GateResult.PASSED);
        }
    }

    // ------------------------------------------------------------ exceptions

    @Nested
    @DisplayName("Exceptions")
    class ExceptionTest {

        private final PolicyEngine engine = PolicyEngine.withBuiltins();
        private final Instant now = Instant.parse("2026-09-15T19:07:03Z");

        private final EffectivePackage pkg = EffectivePackage.withDefaultThreshold("default", true,
                List.of(EffectiveCheck.of("RollbackRequired", Severity.CRITICAL)));

        private final CheckContext ctx = context(rawSql("SUP-3512-013", "create index ix on t(a)"));

        @Test
        @DisplayName("a live exception unblocks, but the finding stays visible")
        void liveExceptionUnblocks() {
            var exception = new ActiveException("RollbackRequired", "SUP-3512-013",
                    now.plus(7, ChronoUnit.DAYS));

            var result = engine.evaluate(ctx, pkg, List.of(exception), now);

            assertThat(result.gate()).isEqualTo(GateResult.PASSED);
            assertThat(result.findings()).hasSize(1);
            assertThat(result.suppressed()).hasSize(1);
        }

        @Test
        @DisplayName("an expired exception does not count")
        void expiredDoesNotCount() {
            var exception = new ActiveException("RollbackRequired", "SUP-3512-013",
                    now.minus(1, ChronoUnit.DAYS));

            var result = engine.evaluate(ctx, pkg, List.of(exception), now);

            assertThat(result.gate()).isEqualTo(GateResult.BLOCKED);
            assertThat(result.suppressed()).isEmpty();
        }

        @Test
        @DisplayName("an exception for another changeset does not count")
        void otherChangesetDoesNotCount() {
            var exception = new ActiveException("RollbackRequired", "SUP-9999-001",
                    now.plus(7, ChronoUnit.DAYS));

            assertThat(engine.evaluate(ctx, pkg, List.of(exception), now).gate())
                    .isEqualTo(GateResult.BLOCKED);
        }

        @Test
        @DisplayName("an exception with no changeset covers the whole check")
        void noChangesetCoversEverything() {
            var exception = new ActiveException("RollbackRequired", null, now.plus(7, ChronoUnit.DAYS));

            assertThat(engine.evaluate(ctx, pkg, List.of(exception), now).gate())
                    .isEqualTo(GateResult.PASSED);
        }

        @Test
        @DisplayName("an exception without an expiry is rejected: permanent exceptions do not exist")
        void withoutExpiryIsRejected() {
            assertThat(Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new ActiveException("RollbackRequired", null, null)).getMessage())
                    .contains("expiry");
        }
    }

    // Catalogue invariants live in BuiltinChecksTest: a top-level test sitting
    // next to @Nested classes never actually runs in the build.
}
