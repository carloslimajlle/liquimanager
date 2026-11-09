package io.github.carloslimajlle.liquimanager.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.Database;
import liquibase.database.core.MSSQLDatabase;
import liquibase.database.core.PostgresDatabase;

/** The phase-3 checks: what they flag, what they spare, and that the SQL Server ones keep quiet elsewhere. */
class Phase3ChecksTest {

    private static final Map<String, PolicyCheck> CHECKS = BuiltinChecks.all().stream()
            .collect(Collectors.toMap(PolicyCheck::code, check -> check));
    private static final DatabaseChangeLog CHANGELOG = new DatabaseChangeLog("master.xml");

    private static Arguments row(String code, Map<String, Object> params, boolean flagged, String why, String... statements) {
        return Arguments.of(code, params, List.of(statements), flagged, why);
    }

    static Stream<Arguments> cases() {
        Map<String, Object> none = Map.of();
        return Stream.of(
                row("CreateIndexOnline", none, true, "index built offline",
                        "CREATE NONCLUSTERED INDEX ix_veiculo_placa ON dbo.veiculo (placa)"),
                row("CreateIndexOnline", none, true, "unique index, bracketed table",
                        "CREATE UNIQUE INDEX ux ON [dbo].[veiculo] (chassi) WITH (FILLFACTOR = 90)"),
                row("CreateIndexOnline", none, false, "online build",
                        "CREATE INDEX ix ON dbo.veiculo (placa) WITH (ONLINE = ON)"),
                row("CreateIndexOnline", none, false, "online build, compact", "create index ix on t(a) with (online=on)"),
                row("CreateIndexOnline", none, false, "no index at all", "ALTER TABLE t ADD c int"),
                row("CreateIndexOnline", Map.of("tables", "dbo.veiculo"), true, "the table is on the big list",
                        "CREATE INDEX ix ON dbo.veiculo (placa)"),
                row("CreateIndexOnline", Map.of("tables", "veiculo"), true, "a bare name matches the schema-qualified one",
                        "CREATE INDEX ix ON dbo.veiculo (placa)"),
                row("CreateIndexOnline", Map.of("tables", "dbo.veiculo"), false, "a small table nobody listed",
                        "CREATE INDEX ix ON dbo.parametro (codigo)"),
                row("CreateIndexOnline", Map.of("tables", "veiculo"), false, "veiculo_historico is not veiculo",
                        "CREATE INDEX ix ON dbo.veiculo_historico (placa)"),

                row("CursorUsageWarn", none, true, "T-SQL cursor", "DECLARE c CURSOR FOR SELECT id FROM t"),
                row("CursorUsageWarn", none, true, "T-SQL cursor over a variable-style name",
                        "declare @c cursor local fast_forward for select id from t"),
                row("CursorUsageWarn", none, true, "PL/pgSQL cursor", "declare c cursor for select id from t"),
                row("CursorUsageWarn", none, false, "a column called cursor", "create table t (id int, cursor int)"),
                row("CursorUsageWarn", none, false, "sp_cursor is a name, not a declaration", "exec sp_cursor_x"),
                row("CursorUsageWarn", none, false, "set-based update", "update t set a = 1 where id in (select id from u)"),

                row("PiiColumnEncryption", none, true, "plain cpf column", "CREATE TABLE p (id int, cpf VARCHAR(11) NOT NULL)"),
                row("PiiColumnEncryption", none, true, "email added later", "ALTER TABLE p ADD email VARCHAR(200)"),
                row("PiiColumnEncryption", none, true, "bracketed name", "CREATE TABLE p (id int, [telefone] varchar(20))"),
                row("PiiColumnEncryption", none, false, "dynamic data masking",
                        "CREATE TABLE p (id int, email VARCHAR(200) MASKED WITH (FUNCTION = 'email()'))"),
                row("PiiColumnEncryption", none, false, "Always Encrypted",
                        "CREATE TABLE p (id int, cpf VARCHAR(11) ENCRYPTED WITH (COLUMN_ENCRYPTION_KEY = k, ENCRYPTION_TYPE = DETERMINISTIC))"),
                row("PiiColumnEncryption", none, false, "no PII-looking column",
                        "CREATE TABLE p (id int, nome VARCHAR(50))"),
                row("PiiColumnEncryption", none, false, "a unique constraint over the column is not a definition",
                        "CREATE TABLE p (id int, CONSTRAINT uq UNIQUE (email))"),
                row("PiiColumnEncryption", none, false, "an index key with a sort order",
                        "CREATE TABLE p (id int, INDEX ix (email desc))"),
                row("PiiColumnEncryption", none, false, "creates no table", "CREATE INDEX ix ON p (email)"),
                row("PiiColumnEncryption", Map.of("columns", "renavam"), true, "a column the operator added",
                        "CREATE TABLE p (id int, renavam VARCHAR(11))"),
                row("PiiColumnEncryption", Map.of("columns", "renavam"), false, "cpf is no longer on the list",
                        "CREATE TABLE p (id int, cpf VARCHAR(11))"));
    }

    private static List<Finding> run(String code, Map<String, Object> params, List<String> statements, Database database) {
        ChangeSet changeSet = new ChangeSet("SUP-1-001", "p.ricardo", false, false, "x.xml", "prd", null, CHANGELOG);
        changeSet.addChange(new RawSQLChange());
        CheckContext ctx = new CheckContext(CHANGELOG, List.of(changeSet), database, cs -> statements,
                Optional.empty(), "PRD", true);
        return CHECKS.get(code).evaluate(ctx, params);
    }

    @ParameterizedTest(name = "{0}: {3}")
    @MethodSource("cases")
    @DisplayName("each phase-3 check flags what it should and spares what it should")
    void behaviour(String code, Map<String, Object> params, List<String> statements, boolean flagged, String why) {
        // The cursor check reads plain SQL and works on any dialect; the others are SQL Server's.
        List<Finding> findings = run(code, params, statements, new MSSQLDatabase());

        if (flagged) {
            assertThat(findings).as("%s should flag: %s", code, why).hasSize(1);
            assertThat(findings.get(0).checkCode()).isEqualTo(code);
            assertThat(findings.get(0).message()).isNotBlank();
        } else {
            assertThat(findings).as("%s must NOT flag: %s", code, why).isEmpty();
        }
    }

    @Test
    @DisplayName("the SQL Server checks say nothing on PostgreSQL, which has neither feature")
    void silentOnOtherDialects() {
        assertThat(run("CreateIndexOnline", Map.of(), List.of("CREATE INDEX ix ON t (a)"), new PostgresDatabase())).isEmpty();
        assertThat(run("PiiColumnEncryption", Map.of(), List.of("CREATE TABLE p (cpf varchar(11))"), new PostgresDatabase()))
                .isEmpty();
        assertThat(run("CursorUsageWarn", Map.of(), List.of("declare c cursor for select 1"), new PostgresDatabase()))
                .hasSize(1);
    }
}
