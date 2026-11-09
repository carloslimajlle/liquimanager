package io.github.carloslimajlle.liquimanager.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.core.PostgresDatabase;

/** Checks that are data: REGEX, SQL_PATTERN, SPEL, chains and copies of builtins. */
class CustomChecksTest {

    private static final DatabaseChangeLog CHANGELOG = new DatabaseChangeLog("master.xml");
    private static final PolicyEngine ENGINE = PolicyEngine.withBuiltins();

    private static ChangeSet changeSet(String id, String comment) {
        ChangeSet cs = new ChangeSet(id, "p.ricardo", false, false, "x.xml", null, null, CHANGELOG);
        cs.addChange(new RawSQLChange());
        if (comment != null) {
            cs.setComments(comment);
        }
        return cs;
    }

    private static EvaluationResult run(EffectiveCheck check, ChangeSet cs, String... sql) {
        CheckContext ctx = new CheckContext(CHANGELOG, List.of(cs), new PostgresDatabase(),
                c -> List.of(sql), Optional.empty(), "PRD", true);
        return ENGINE.evaluate(ctx, new EffectivePackage("p", 4, List.of(check)), List.of(), Instant.now());
    }

    private static EffectiveCheck custom(String code, String kind, int severity, Map<String, Object> params, String message) {
        return new EffectiveCheck(code, severity, params, kind, null, message, List.of());
    }

    // ------------------------------------------------------------------ REGEX

    @Test
    @DisplayName("REGEX forbids a pattern in the SQL and uses the severity of the package")
    void regexForbids() {
        EffectiveCheck check = custom("NoNolockInViews", "REGEX", 3,
                Map.of("pattern", "with\\s*\\(nolock\\)", "appliesTo", "SQL"), "NOLOCK em <CHANGESET_ID>");

        EvaluationResult result = run(check, changeSet("SUP-1", null), "create view v as select * from t with (nolock)");

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).message()).isEqualTo("NOLOCK em SUP-1");
        assertThat(result.findings().get(0).severity()).isEqualTo(3);
        assertThat(result.findings().get(0).checkCode()).isEqualTo("NoNolockInViews");
    }

    @Test
    @DisplayName("REGEX spares SQL without the pattern")
    void regexSpares() {
        EffectiveCheck check = custom("NoNolockInViews", "REGEX", 3, Map.of("pattern", "nolock"), null);

        assertThat(run(check, changeSet("SUP-1", null), "select 1").findings()).isEmpty();
    }

    @Test
    @DisplayName("REGEX with mustMatch requires the pattern, here in the comment")
    void regexRequires() {
        EffectiveCheck check = custom("TicketInComment", "REGEX", 2,
                Map.of("pattern", "SUP-\\d+", "appliesTo", "COMMENT", "mustMatch", true), null);

        assertThat(run(check, changeSet("A", "sem ticket")).findings()).hasSize(1);
        assertThat(run(check, changeSet("B", "ver SUP-3456")).findings()).isEmpty();
    }

    @Test
    @DisplayName("an invalid regex is silent, never a finding")
    void invalidRegexIsSilent() {
        EffectiveCheck check = custom("Broken", "REGEX", 4, Map.of("pattern", "([unclosed"), null);

        assertThat(run(check, changeSet("A", null), "select 1").findings()).isEmpty();
    }

    @Test
    @DisplayName("REGEX over SQL stays quiet when no SQL could be generated")
    void noSqlNoVerdict() {
        EffectiveCheck check = custom("Req", "REGEX", 4, Map.of("pattern", "x", "mustMatch", true), null);

        assertThat(run(check, changeSet("A", null)).findings()).isEmpty();
    }

    // ------------------------------------------------------------ SQL_PATTERN

    private static EffectiveCheck pattern(Map<String, Object> params) {
        return custom("TableRule", "SQL_PATTERN", 3, params, null);
    }

    @Test
    @DisplayName("SQL_PATTERN: tables starting with tb_pessoa that have a cpf column need a foreign key")
    void sqlPatternRequiredConstraint() {
        EffectiveCheck check = pattern(Map.of("statementType", "CREATE_TABLE", "operator", "STARTS_WITH",
                "tables", "tb_pessoa", "columns", "cpf", "requiredConstraints", List.of("FOREIGNKEY")));

        assertThat(run(check, changeSet("A", null), "CREATE TABLE tb_pessoa_fisica (id int, cpf varchar(11))")
                .findings()).hasSize(1);
        assertThat(run(check, changeSet("B", null),
                "CREATE TABLE tb_pessoa_fisica (id int, cpf varchar(11), CONSTRAINT fk FOREIGN KEY (id) REFERENCES x(id))")
                .findings()).isEmpty();
        assertThat(run(check, changeSet("C", null), "CREATE TABLE tb_outro (id int, cpf varchar(11))")
                .findings()).as("another table").isEmpty();
        assertThat(run(check, changeSet("D", null), "CREATE TABLE tb_pessoa_fisica (id int, nome varchar(5))")
                .findings()).as("no cpf column").isEmpty();
    }

    @Test
    @DisplayName("SQL_PATTERN without required constraints: matching IS the finding")
    void sqlPatternForbidsTheOperation() {
        EffectiveCheck check = pattern(Map.of("statementType", "DROP", "operator", "ALL"));

        assertThat(run(check, changeSet("A", null), "DROP TABLE dbo.cliente").findings()).hasSize(1);
        assertThat(run(check, changeSet("B", null), "CREATE TABLE t (id int)").findings()).isEmpty();
    }

    @Test
    @DisplayName("SQL_PATTERN message variables are filled")
    void sqlPatternMessage() {
        EffectiveCheck check = custom("Msg", "SQL_PATTERN", 2,
                Map.of("statementType", "DROP", "operator", "ALL"), "<AUTHOR> removeu <TABLE_NAME> em <ENV>");

        assertThat(run(check, changeSet("A", null), "DROP TABLE [dbo].[cliente]").findings().get(0).message())
                .isEqualTo("p.ricardo removeu dbo.cliente em PRD");
    }

    @Test
    @DisplayName("SQL_PATTERN operators: ENDS_WITH, CONTAINS and REGEXP")
    void sqlPatternOperators() {
        String sql = "DROP TABLE tb_log_2024";
        assertThat(run(pattern(Map.of("statementType", "DROP", "operator", "ENDS_WITH", "tables", "_2024")),
                changeSet("A", null), sql).findings()).hasSize(1);
        assertThat(run(pattern(Map.of("statementType", "DROP", "operator", "CONTAINS", "tables", "log")),
                changeSet("B", null), sql).findings()).hasSize(1);
        assertThat(run(pattern(Map.of("statementType", "DROP", "operator", "REGEXP", "tables", "^tb_log_\\d+$")),
                changeSet("C", null), sql).findings()).hasSize(1);
        assertThat(run(pattern(Map.of("statementType", "DROP", "operator", "REGEXP", "tables", "([bad")),
                changeSet("D", null), sql).findings()).as("invalid regex").isEmpty();
    }

    @Test
    @DisplayName("an unknown statement type never matches")
    void unknownStatementType() {
        assertThat(run(pattern(Map.of("statementType", "NOT_A_TYPE")), changeSet("A", null), "DROP TABLE t").findings())
                .isEmpty();
    }

    // ------------------------------------------------------- copy, SPEL, chain

    @Test
    @DisplayName("a copy of a builtin has its own code but runs the parent's logic")
    void copyOfBuiltin() {
        EffectiveCheck copy = new EffectiveCheck("DropColumnForbidden", 4, Map.of(), "BUILTIN",
                "DropColumnWarn", null, List.of());

        EvaluationResult result = run(copy, changeSet("A", null), "ALTER TABLE t DROP COLUMN c");

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).checkCode()).isEqualTo("DropColumnForbidden");
        assertThat(result.gate()).isEqualTo(GateResult.BLOCKED);
    }

    // ------------------------------------------------------------------ SPEL

    private static EffectiveCheck spel(String expression) {
        return custom("Expr", "SPEL", 4, Map.of("expression", expression), null);
    }

    @Test
    @DisplayName("SPEL: the example of the spec, a changeset without a comment in production")
    void spelRuns() {
        EffectiveCheck check = spel("changeSet.comments == null && env == 'PRD'");

        assertThat(run(check, changeSet("A", null), "select 1").findings()).hasSize(1);
        assertThat(run(check, changeSet("B", "SUP-1"), "select 1").findings()).isEmpty();
    }

    @Test
    @DisplayName("SPEL reads the SQL, the author, the id and the type of the change")
    void spelVariables() {
        assertThat(run(spel("sql matches '.*drop\s+table.*'"), changeSet("A", null), "DROP TABLE t").findings()).hasSize(1);
        assertThat(run(spel("sql matches '.*drop\s+table.*'"), changeSet("A", null), "select 1").findings()).isEmpty();
        assertThat(run(spel("changeSet.author == 'p.ricardo' and changeSet.id == 'A'"), changeSet("A", null), "x").findings()).hasSize(1);
        assertThat(run(spel("change.type == 'sql'"), changeSet("A", null), "x").findings()).hasSize(1);
        assertThat(run(spel("change.type == 'createTable'"), changeSet("A", null), "x").findings()).isEmpty();
    }

    @Test
    @DisplayName("SPEL: one finding per changeset however many changes match")
    void spelOneFindingPerChangeSet() {
        ChangeSet cs = changeSet("A", null);
        cs.addChange(new RawSQLChange());

        assertThat(run(spel("change.type == 'sql'"), cs, "x").findings()).hasSize(1);
    }

    @Test
    @DisplayName("SPEL stays silent: a name that does not exist, a result that is not a boolean, a null in the way, a broken expression")
    void spelSilentWhenUnsure() {
        for (String expression : List.of("nope == 1", "changeSet.nope", "'text'", "changeSet.comments.length() > 3", "((", "")) {
            assertThat(run(spel(expression), changeSet("A", null), "select 1").findings()).as(expression).isEmpty();
        }
    }

    @Test
    @DisplayName("SPEL cannot call methods, reach types or build objects")
    void spelIsSandboxed() {
        for (String expression : List.of("T(java.lang.Runtime).getRuntime().exec('calc') == null",
                "new java.lang.String('x') == 'x'", "env.toString() == 'PRD'", "changeSet.getClass() != null",
                "#root.class.name != null")) {
            assertThat(run(spel(expression), changeSet("A", null), "select 1").findings()).as(expression).isEmpty();
        }
    }

    @Test
    @DisplayName("the save-time check refuses what the engine would never run")
    void spelProblem() {
        assertThat(io.github.carloslimajlle.liquimanager.policy.builtin.CustomChecks.spelProblem("env == 'PRD'")).isNull();
        assertThat(io.github.carloslimajlle.liquimanager.policy.builtin.CustomChecks.spelProblem("((")).contains("does not parse");
        assertThat(io.github.carloslimajlle.liquimanager.policy.builtin.CustomChecks.spelProblem(" ")).contains("empty");
        assertThat(io.github.carloslimajlle.liquimanager.policy.builtin.CustomChecks.spelProblem("1".repeat(501))).contains("longer");
    }

    @Test
    @DisplayName("a chain reports ONE finding per changeset with the worst severity of its members")
    void chain() {
        EffectiveCheck drop = new EffectiveCheck("DropColumnWarn", 3, Map.of());
        EffectiveCheck update = new EffectiveCheck("UpdateWithoutWhere", 2, Map.of());
        EffectiveCheck chain = new EffectiveCheck("DataStandards", 1, Map.of(), "CHAINED", null, null,
                List.of(drop, update));

        EvaluationResult result = run(chain, changeSet("A", null),
                "ALTER TABLE t DROP COLUMN c", "UPDATE t SET x = 1");

        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).checkCode()).isEqualTo("DataStandards");
        assertThat(result.findings().get(0).severity()).as("the worst of 3 and 2").isEqualTo(3);
        assertThat(result.findings().get(0).message()).contains("DropColumnWarn", "UpdateWithoutWhere");
    }

    @Test
    @DisplayName("a chain whose members find nothing finds nothing")
    void cleanChain() {
        EffectiveCheck chain = new EffectiveCheck("DataStandards", 1, Map.of(), "CHAINED", null, null,
                List.of(new EffectiveCheck("DropColumnWarn", 3, Map.of())));

        assertThat(run(chain, changeSet("A", null), "select 1").findings()).isEmpty();
    }
}
