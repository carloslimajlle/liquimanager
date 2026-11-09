package io.github.carloslimajlle.liquimanager.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import liquibase.Labels;
import liquibase.change.ColumnConfig;
import liquibase.change.core.AddColumnChange;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.LoadDataChange;
import liquibase.change.core.ModifyDataTypeChange;
import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.Database;
import liquibase.database.core.MSSQLDatabase;
import liquibase.database.core.PostgresDatabase;
import liquibase.precondition.core.PreconditionContainer;
import liquibase.precondition.core.SqlPrecondition;
import io.github.carloslimajlle.liquimanager.policy.builtin.Phase2Checks;

/**
 * The phase-2 checks: for each, what it must flag and what it must leave alone (a false positive blocks a deployment).
 * The ones that read SQL are in the table; the ones that read the changeset's model, or the changelog around it, follow.
 */
class Phase2ChecksTest {

    private static final Map<String, PolicyCheck> CHECKS = BuiltinChecks.all().stream()
            .collect(Collectors.toMap(PolicyCheck::code, check -> check));

    // ----------------------------------------------------------------- helpers

    private static ChangeSet changeSet(DatabaseChangeLog log, String id) {
        return new ChangeSet(id, "p.ricardo", false, false, "x.xml", "prd", null, log);
    }

    private static List<Finding> sql(String code, Map<String, Object> params, Database database, String... statements) {
        DatabaseChangeLog log = new DatabaseChangeLog("master.xml");
        ChangeSet changeSet = changeSet(log, "SUP-1-001");
        changeSet.addChange(new RawSQLChange());
        CheckContext ctx = new CheckContext(log, List.of(changeSet), database, cs -> List.of(statements), Optional.empty(), "PRD", true);
        return CHECKS.get(code).evaluate(ctx, params);
    }

    private static Arguments row(String code, Map<String, Object> params, boolean flagged, String why, String... statements) {
        return Arguments.of(code, params, List.of(statements), flagged, why);
    }

    // ------------------------------------------------------------ SQL, in a table

    static Stream<Arguments> sqlCases() {
        Map<String, Object> none = Map.of();
        return Stream.of(
                row("AlterRoleMembership", none, true, "ALTER ROLE ADD MEMBER", "ALTER ROLE db_datareader ADD MEMBER ana"),
                row("AlterRoleMembership", none, true, "sp_addrolemember", "EXEC sp_addrolemember 'db_owner', 'ana'"),
                row("AlterRoleMembership", none, true, "a server role", "ALTER SERVER ROLE sysadmin ADD MEMBER ana"),
                row("AlterRoleMembership", none, true, "DROP MEMBER too", "ALTER ROLE r DROP MEMBER ana"),
                row("AlterRoleMembership", none, false, "a role's own settings", "ALTER ROLE r WITH NAME = r2"),
                row("AlterRoleMembership", none, false, "a plain grant", "GRANT SELECT ON t TO r"),

                row("IdentityVsSequence", none, true, "identity and next value for",
                        "CREATE TABLE t (id INT IDENTITY(1,1), n INT DEFAULT NEXT VALUE FOR seq_n)"),
                row("IdentityVsSequence", none, true, "serial and nextval",
                        "create table t (id serial, n int default nextval('s'))"),
                row("IdentityVsSequence", none, true, "sequence default declared first",
                        "CREATE TABLE t (n INT DEFAULT NEXT VALUE FOR s, id INT IDENTITY)"),
                row("IdentityVsSequence", none, false, "identity alone", "CREATE TABLE t (id INT IDENTITY(1,1), n INT)"),
                row("IdentityVsSequence", none, false, "a sequence default alone", "CREATE TABLE t (n INT DEFAULT NEXT VALUE FOR s)"),
                row("IdentityVsSequence", none, false, "two tables, one of each",
                        "CREATE TABLE t (id INT IDENTITY); CREATE TABLE u (n INT DEFAULT NEXT VALUE FOR s)"),

                row("CrossDatabaseReference", none, true, "bracketed database", "SELECT * FROM [Outro].dbo.tabela"),
                row("CrossDatabaseReference", none, true, "an insert into another database", "insert into otherdb.dbo.t select 1"),
                row("CrossDatabaseReference", none, true, "an alter of a three-part name", "ALTER TABLE x.y.z ADD c int"),
                row("CrossDatabaseReference", none, false, "schema and table only", "SELECT * FROM dbo.tabela"),
                row("CrossDatabaseReference", none, false, "a column reference is not a database", "select a.b.c from t"),
                row("CrossDatabaseReference", Map.of("allowed", "core"), false, "the project's own database is allowed",
                        "select * from core.dbo.t"),
                row("CrossDatabaseReference", Map.of("allowed", "core"), true, "another one still is",
                        "select * from outro.dbo.t"),

                row("CollationExplicit", none, true, "a column with a collation",
                        "CREATE TABLE t (n VARCHAR(10) COLLATE Latin1_General_CI_AS)"),
                row("CollationExplicit", none, true, "added with one", "ALTER TABLE t ADD n varchar(5) COLLATE sql_latin1_general_cp1_ci_as"),
                row("CollationExplicit", none, false, "a collation in a query, not a column",
                        "select * from t order by n collate sql_latin1_general_cp1_ci_as"),
                row("CollationExplicit", Map.of("database", "Latin1_General_CI_AS"), false, "the same as the database's",
                        "CREATE TABLE t (n VARCHAR(10) COLLATE Latin1_General_CI_AS)"),
                row("CollationExplicit", Map.of("database", "Latin1_General_CI_AS"), true, "a different one",
                        "CREATE TABLE t (n VARCHAR(10) COLLATE Latin1_General_BIN)"),

                row("PiiColumnClassification", none, true, "a cpf column", "CREATE TABLE p (id int, cpf VARCHAR(11))"),
                row("PiiColumnClassification", none, true, "an email added later", "ALTER TABLE p ADD email VARCHAR(200)"),
                row("PiiColumnClassification", none, false, "no personal column", "CREATE TABLE p (id int, nome VARCHAR(50))"),
                row("PiiColumnClassification", none, false, "a constraint over it is no definition",
                        "CREATE TABLE p (id int, CONSTRAINT uq UNIQUE (email))"),
                row("PiiColumnClassification", Map.of("columns", "renavam"), true, "a column the operator added",
                        "CREATE TABLE p (id int, renavam VARCHAR(11))"),

                row("SeedDataWithPii", none, true, "a formatted CPF", "INSERT INTO p (cpf) VALUES ('529.982.247-25')"),
                row("SeedDataWithPii", none, true, "an unformatted CPF", "INSERT INTO p (cpf) VALUES ('52998224725')"),
                row("SeedDataWithPii", none, true, "a real-looking e-mail", "INSERT INTO u (e) VALUES ('joao.silva@gmail.com')"),
                row("SeedDataWithPii", none, false, "a CPF with wrong check digits", "INSERT INTO p (cpf) VALUES ('123.456.789-00')"),
                row("SeedDataWithPii", none, false, "a run of one digit", "INSERT INTO p (cpf) VALUES ('111.111.111-11')"),
                row("SeedDataWithPii", none, false, "an example domain", "INSERT INTO u (e) VALUES ('ana@example.com')"),
                row("SeedDataWithPii", none, false, "a local domain", "INSERT INTO u (e) VALUES ('admin@liquimanager.local')"),
                row("SeedDataWithPii", none, false, "not an insert", "UPDATE p SET cpf = '529.982.247-25' WHERE id = 1"),
                row("SeedDataWithPii", none, false, "an id that is only numbers", "INSERT INTO t (id) VALUES (9876543210)"));
    }

    @ParameterizedTest(name = "{0}: {4}")
    @MethodSource("sqlCases")
    @DisplayName("each SQL check flags what it should and spares what it should")
    void sqlBehaviour(String code, Map<String, Object> params, List<String> statements, boolean flagged, String why) {
        List<Finding> findings = sql(code, params, new PostgresDatabase(), statements.toArray(String[]::new));
        if (flagged) {
            assertThat(findings).as("%s should flag: %s", code, why).hasSize(1);
            assertThat(findings.get(0).checkCode()).isEqualTo(code);
            assertThat(findings.get(0).message()).isNotBlank();
        } else {
            assertThat(findings).as("%s must NOT flag: %s", code, why).isEmpty();
        }
    }

    // ------------------------------------------------------- runAlways, runOnChange

    @Test
    @DisplayName("runAlways and runOnChange are flagged without a comment and spared with one")
    void runAlwaysAndRunOnChange() {
        DatabaseChangeLog log = new DatabaseChangeLog("master.xml");
        ChangeSet always = new ChangeSet("A", "p", true, false, "x.xml", "prd", null, log);
        ChangeSet onChange = new ChangeSet("B", "p", false, true, "x.xml", "prd", null, log);
        ChangeSet justified = new ChangeSet("C", "p", true, true, "x.xml", "prd", null, log);
        justified.setComments("Recria a view a cada deploy: o dono pediu");
        ChangeSet plain = new ChangeSet("D", "p", false, false, "x.xml", "prd", null, log);
        CheckContext ctx = new CheckContext(log, List.of(always, onChange, justified, plain), new PostgresDatabase(), cs -> List.of(),
                Optional.empty(), "PRD", true);

        assertThat(CHECKS.get("RunAlwaysForbidden").evaluate(ctx, Map.of())).extracting(Finding::message).singleElement().asString().contains("A");
        assertThat(CHECKS.get("RunOnChangeReview").evaluate(ctx, Map.of())).extracting(Finding::message).singleElement().asString().contains("B");
    }

    // -------------------------------------------------------------------- comments

    private static CreateTableChange table(String name, String remarks, ColumnConfig... columns) {
        CreateTableChange create = new CreateTableChange();
        create.setTableName(name);
        create.setRemarks(remarks);
        for (ColumnConfig column : columns) {
            create.addColumn(column);
        }
        return create;
    }

    private static ColumnConfig column(String name, String remarks) {
        return new ColumnConfig().setName(name).setType("int").setRemarks(remarks);
    }

    private static List<Finding> model(String code, Map<String, Object> params, Database database, ChangeSet changeSet) {
        DatabaseChangeLog log = new DatabaseChangeLog("master.xml");
        CheckContext ctx = new CheckContext(log, List.of(changeSet), database, cs -> List.of(), Optional.empty(), "PRD", true);
        return CHECKS.get(code).evaluate(ctx, params);
    }

    @Test
    @DisplayName("a table without remarks is flagged, one with them is not")
    void tableComment() {
        DatabaseChangeLog log = new DatabaseChangeLog("master.xml");
        ChangeSet bare = changeSet(log, "1");
        bare.addChange(table("veiculo", null));
        ChangeSet documented = changeSet(log, "2");
        documented.addChange(table("agendamento", "Janelas de agendamento"));

        assertThat(model("TableCommentRequired", Map.of(), new PostgresDatabase(), bare)).hasSize(1);
        assertThat(model("TableCommentRequired", Map.of(), new PostgresDatabase(), documented)).isEmpty();
    }

    @Test
    @DisplayName("columns without remarks are flagged once per changeset, in createTable and addColumn alike")
    void columnComment() {
        DatabaseChangeLog log = new DatabaseChangeLog("master.xml");
        ChangeSet created = changeSet(log, "1");
        created.addChange(table("veiculo", "x", column("id", null), column("placa", "A placa"), column("chassi", " ")));
        ChangeSet added = changeSet(log, "2");
        AddColumnChange add = new AddColumnChange();
        add.setTableName("veiculo");
        liquibase.change.AddColumnConfig ano = new liquibase.change.AddColumnConfig();
        ano.setName("ano");
        ano.setType("int");
        add.addColumn(ano);
        added.addChange(add);
        ChangeSet fine = changeSet(log, "3");
        fine.addChange(table("t", "x", column("id", "chave")));

        List<Finding> first = model("ColumnCommentRequired", Map.of(), new PostgresDatabase(), created);
        assertThat(first).hasSize(1);
        assertThat(first.get(0).message()).contains("id").contains("chassi").doesNotContain("placa");
        assertThat(model("ColumnCommentRequired", Map.of(), new PostgresDatabase(), added)).hasSize(1);
        assertThat(model("ColumnCommentRequired", Map.of(), new PostgresDatabase(), fine)).isEmpty();
    }

    // ------------------------------------------------------------------ pii label

    @Test
    @DisplayName("a personal column is spared when the changeset carries the pii label")
    void piiLabel() {
        DatabaseChangeLog log = new DatabaseChangeLog("master.xml");
        ChangeSet labelled = changeSet(log, "1");
        labelled.setLabels(new Labels("release-2.0.0, PII"));
        ChangeSet unlabelled = changeSet(log, "2");
        unlabelled.setLabels(new Labels("release-2.0.0"));
        Function<ChangeSet, List<String>> sql = cs -> List.of("CREATE TABLE p (id int, cpf VARCHAR(11))");

        assertThat(CHECKS.get("PiiColumnClassification").evaluate(
                new CheckContext(log, List.of(labelled), new PostgresDatabase(), sql, Optional.empty(), "PRD", true), Map.of())).isEmpty();
        assertThat(CHECKS.get("PiiColumnClassification").evaluate(
                new CheckContext(log, List.of(unlabelled), new PostgresDatabase(), sql, Optional.empty(), "PRD", true), Map.of())).hasSize(1);
    }

    // ------------------------------------------------------------- reserved words

    @Test
    @DisplayName("an object named with a reserved word is flagged, an ordinary name is not, and without a dialect nothing is said")
    void reservedWords() {
        DatabaseChangeLog log = new DatabaseChangeLog("master.xml");
        ChangeSet reserved = changeSet(log, "1");
        reserved.addChange(table("pedido", "x", column("order", "x"), column("placa", "x")));
        ChangeSet fine = changeSet(log, "2");
        fine.addChange(table("veiculo", "x", column("placa", "x")));

        assertThat(model("ReservedWordAsIdentifier", Map.of(), new MSSQLDatabase(), reserved)).singleElement()
                .extracting(Finding::message).asString().contains("order");
        assertThat(model("ReservedWordAsIdentifier", Map.of(), new MSSQLDatabase(), fine)).isEmpty();
        assertThat(model("ReservedWordAsIdentifier", Map.of(), null, reserved)).isEmpty();
    }

    // ------------------------------------------------------------------ narrowing

    @Test
    @DisplayName("narrows: certain only when the new type holds less")
    void narrowsTable() {
        Map<String[], Boolean> cases = new java.util.LinkedHashMap<>();
        cases.put(new String[] {"varchar(200)", "varchar(50)"}, true);
        cases.put(new String[] {"nvarchar(100)", "NVARCHAR(10)"}, true);
        cases.put(new String[] {"varchar(max)", "varchar(50)"}, true);
        cases.put(new String[] {"bigint", "int"}, true);
        cases.put(new String[] {"int", "smallint"}, true);
        cases.put(new String[] {"decimal(18,2)", "decimal(10,2)"}, true);
        cases.put(new String[] {"numeric(18,4)", "numeric(18,2)"}, true);
        cases.put(new String[] {"character varying(100)", "character varying(20)"}, true);
        cases.put(new String[] {"varchar(50)", "varchar(200)"}, false);
        cases.put(new String[] {"varchar(50)", "varchar(max)"}, false);
        cases.put(new String[] {"int", "bigint"}, false);
        cases.put(new String[] {"decimal(10,2)", "decimal(18,2)"}, false);
        cases.put(new String[] {"varchar(50)", "varchar(50)"}, false);
        cases.put(new String[] {"text", "varchar(50)"}, false);
        cases.put(new String[] {"bigint", "varchar(5)"}, false);
        cases.put(new String[] {"datetime2", "date"}, false);
        cases.forEach((types, expected) ->
                assertThat(Phase2Checks.ModifyDataTypeNarrowing.narrows(types[0], types[1])).as(types[0] + " -> " + types[1]).isEqualTo(expected));
    }

    private static List<Finding> narrowing(String earlierType, String newType, boolean createdEarlier) {
        DatabaseChangeLog log = new DatabaseChangeLog("master.xml");
        ChangeSet create = changeSet(log, "SUP-1-001");
        create.addChange(table("veiculo", "x", new ColumnConfig().setName("placa").setType(earlierType).setRemarks("x")));
        ChangeSet modify = changeSet(log, "SUP-2-001");
        ModifyDataTypeChange change = new ModifyDataTypeChange();
        change.setTableName("veiculo");
        change.setColumnName("placa");
        change.setNewDataType(newType);
        modify.addChange(change);
        if (createdEarlier) {
            log.addChangeSet(create);
        }
        log.addChangeSet(modify);
        CheckContext ctx = new CheckContext(log, List.of(modify), new MSSQLDatabase(),
                cs -> cs == modify ? List.of("ALTER TABLE dbo.veiculo ALTER COLUMN placa " + newType) : List.of(),
                Optional.empty(), "PRD", true);
        return CHECKS.get("ModifyDataTypeNarrowing").evaluate(ctx, Map.of());
    }

    @Test
    @DisplayName("a narrowed column is flagged when the changelog itself said how big it was; if it never did, nothing is said")
    void narrowingInAChangelog() {
        assertThat(narrowing("VARCHAR(200)", "VARCHAR(50)", true)).singleElement().extracting(Finding::message).asString()
                .contains("veiculo.placa").contains("varchar(200)").contains("varchar(50)");
        assertThat(narrowing("VARCHAR(50)", "VARCHAR(200)", true)).as("growing is fine").isEmpty();
        assertThat(narrowing("VARCHAR(200)", "VARCHAR(50)", false)).as("the earlier size is unknown").isEmpty();
    }

    // ---------------------------------------------------------------------- dedup

    private static List<Finding> dedup(Function<DatabaseChangeLog, List<ChangeSet>> earlier, ChangeSetSetup target, String... targetSql) {
        DatabaseChangeLog log = new DatabaseChangeLog("master.xml");
        List<ChangeSet> before = earlier.apply(log);
        before.forEach(log::addChangeSet);
        ChangeSet unique = changeSet(log, "SUP-9-001");
        target.apply(unique);
        log.addChangeSet(unique);
        Map<String, List<String>> sqlById = new java.util.HashMap<>();
        for (ChangeSet cs : before) {
            sqlById.put(cs.getId(), SQL_OF.getOrDefault(cs.getId(), List.of()));
        }
        sqlById.put("SUP-9-001", List.of(targetSql));
        CheckContext ctx = new CheckContext(log, List.of(unique), new MSSQLDatabase(), cs -> sqlById.getOrDefault(cs.getId(), List.of()),
                Optional.empty(), "PRD", true);
        return CHECKS.get("UniqueIndexRequiresDedup").evaluate(ctx, Map.of());
    }

    private interface ChangeSetSetup {
        void apply(ChangeSet changeSet);
    }

    private static final Map<String, List<String>> SQL_OF = new java.util.HashMap<>();

    private static List<ChangeSet> earlier(DatabaseChangeLog log, String id, String comment, String... statements) {
        ChangeSet changeSet = changeSet(log, id);
        changeSet.setComments(comment);
        SQL_OF.put(id, List.of(statements));
        return List.of(changeSet);
    }

    @Test
    @DisplayName("a UNIQUE on a table that already exists is blocked unless duplicates were dealt with first")
    void uniqueOnAnExistingTable() {
        String add = "ALTER TABLE dbo.veiculo ADD CONSTRAINT uk_veiculo_placa UNIQUE (placa)";

        assertThat(dedup(log -> List.of(), cs -> { }, add)).as("nothing deals with duplicates").hasSize(1)
                .first().extracting(Finding::severity).isEqualTo(Severity.BLOCKER);
        assertThat(dedup(log -> List.of(), cs -> { }, "CREATE UNIQUE NONCLUSTERED INDEX ux ON [dbo].[veiculo] (placa)")).hasSize(1);
        assertThat(dedup(log -> List.of(), cs -> { }, "ALTER TABLE veiculo ADD PRIMARY KEY (id)")).hasSize(1);

        assertThat(dedup(log -> List.of(), cs -> { }, "CREATE TABLE dbo.veiculo (id int, placa varchar(10))", add))
                .as("the table is new in this very changeset").isEmpty();
        assertThat(dedup(log -> List.of(), cs -> { }, "DELETE FROM dbo.veiculo WHERE id NOT IN (SELECT MIN(id) FROM dbo.veiculo GROUP BY placa)", add))
                .as("duplicates removed first, in the same changeset").isEmpty();
        assertThat(dedup(log -> earlier(log, "SUP-3-001", null, "DELETE FROM veiculo WHERE id IN (SELECT id FROM dups)"), cs -> { }, add))
                .as("an earlier changeset deletes from the table").isEmpty();
        assertThat(dedup(log -> earlier(log, "SUP-3-002a-dedup-placa", null, "update veiculo set placa = placa + id where id > 1"), cs -> { }, add))
                .as("an earlier changeset that says it is the dedup").isEmpty();
        assertThat(dedup(log -> earlier(log, "SUP-3-003", "Remove duplicados de veiculo antes da UK", "select 1 from veiculo"), cs -> { }, add))
                .as("the comment says so").isEmpty();
        assertThat(dedup(log -> earlier(log, "SUP-3-004", null, "DELETE FROM veiculo_historico"), cs -> { }, add))
                .as("deleting from ANOTHER table says nothing about this one").hasSize(1);
    }

    @Test
    @DisplayName("a precondition that counts or groups counts as dealing with duplicates")
    void preconditionDedup() {
        SqlPrecondition counts = new SqlPrecondition();
        counts.setExpectedResult("0");
        counts.setSql("select count(*) from (select placa from veiculo group by placa having count(*) > 1) d");
        PreconditionContainer container = new PreconditionContainer();
        container.addNestedPrecondition(counts);

        assertThat(dedup(log -> List.of(), cs -> cs.setPreconditions(container), "ALTER TABLE veiculo ADD UNIQUE (placa)")).isEmpty();

        SqlPrecondition unrelated = new SqlPrecondition();
        unrelated.setExpectedResult("1");
        unrelated.setSql("select 1");
        PreconditionContainer other = new PreconditionContainer();
        other.addNestedPrecondition(unrelated);
        assertThat(dedup(log -> List.of(), cs -> cs.setPreconditions(other), "ALTER TABLE veiculo ADD UNIQUE (placa)")).hasSize(1);
    }

    // ------------------------------------------------------------------ the chain

    @Test
    @DisplayName("ChangesetStandards says one thing per changeset, with the worst severity among the checks it chains")
    void chained() {
        DatabaseChangeLog log = new DatabaseChangeLog("master.xml");
        ChangeSet bare = changeSet(log, "SUP-1-001");
        bare.addChange(new RawSQLChange());
        ChangeSet good = changeSet(log, "SUP-1-002");
        good.addChange(table("t", "x", column("id", "x")));
        good.setComments("Cria a tabela t");
        good.setLabels(new Labels("release-1.0.0"));
        CheckContext ctx = new CheckContext(log, List.of(bare, good), new PostgresDatabase(), cs -> List.of(), Optional.empty(), "PRD", true);

        List<Finding> findings = CHECKS.get("ChangesetStandards").evaluate(ctx, Map.of());
        assertThat(findings).as("one finding, for the changeset that fails").hasSize(1);
        assertThat(findings.get(0).changesetKey()).isEqualTo(CheckContext.keyOf(bare));
        assertThat(findings.get(0).message()).contains("RollbackRequired").contains("ChangesetCommentRequired").contains("LabelRequired");

        int worst = Stream.of(new String[] {"RollbackRequired"}, new String[] {"ChangesetCommentRequired"}, new String[] {"LabelRequired"})
                .flatMap(code -> CHECKS.get(code[0]).evaluate(ctx, Map.of()).stream()).mapToInt(Finding::severity).max().orElseThrow();
        assertThat(findings.get(0).severity()).isEqualTo(worst);

        assertThat(CHECKS.get("ChangesetStandards").evaluate(ctx, Map.of("checks", "LabelRequired"))).hasSize(1);
        assertThat(CHECKS.get("ChangesetStandards").evaluate(ctx, Map.of("checks", "NotAChainableCheck"))).isEmpty();
    }

    // ------------------------------------------------------------------- loadData

    @Test
    @DisplayName("a loadData that generates more SQL than the limit is flagged; a small one, or a big non-loadData, is not")
    void loadDataSize() {
        DatabaseChangeLog log = new DatabaseChangeLog("master.xml");
        ChangeSet load = changeSet(log, "SUP-1-001");
        load.addChange(new LoadDataChange());
        ChangeSet raw = changeSet(log, "SUP-1-002");
        raw.addChange(new RawSQLChange());
        String big = "x".repeat(6_000_000);

        Function<ChangeSet, List<String>> sizes = cs -> List.of(big);
        assertThat(CHECKS.get("LoadDataFileSize").evaluate(
                new CheckContext(log, List.of(load), null, sizes, Optional.empty(), "PRD", true), Map.of())).hasSize(1);
        assertThat(CHECKS.get("LoadDataFileSize").evaluate(
                new CheckContext(log, List.of(load), null, cs -> List.of("insert into t values (1)"), Optional.empty(), "PRD", true), Map.of())).isEmpty();
        assertThat(CHECKS.get("LoadDataFileSize").evaluate(
                new CheckContext(log, List.of(raw), null, sizes, Optional.empty(), "PRD", true), Map.of())).isEmpty();
        assertThat(CHECKS.get("LoadDataFileSize").evaluate(
                new CheckContext(log, List.of(load), null, sizes, Optional.empty(), "PRD", true), Map.of("maxMb", 10))).isEmpty();
    }
}
