package io.github.carloslimajlle.liquimanager.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.core.PostgresDatabase;

/**
 * The phase-1 checks that read the generated SQL, one row per behaviour: what it must
 * flag AND what it must leave alone. The second half matters as much as the first — a
 * pattern that cries wolf blocks a deployment, so each row of "must not flag" is a
 * false positive someone could otherwise ship.
 */
class SqlChecksTest {

    private static final Map<String, PolicyCheck> CHECKS = BuiltinChecks.all().stream()
            .collect(Collectors.toMap(PolicyCheck::code, check -> check));
    private static final DatabaseChangeLog CHANGELOG = new DatabaseChangeLog("master.xml");

    private static Arguments flags(String code, String why, String... statements) {
        return Arguments.of(code, Map.of(), List.of(statements), true, why);
    }

    private static Arguments flagsWith(String code, Map<String, Object> params, String why, String... statements) {
        return Arguments.of(code, params, List.of(statements), true, why);
    }

    private static Arguments spares(String code, String why, String... statements) {
        return Arguments.of(code, Map.of(), List.of(statements), false, why);
    }

    private static Arguments sparesWith(String code, Map<String, Object> params, String why, String... statements) {
        return Arguments.of(code, params, List.of(statements), false, why);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                flags("DropColumnWarn", "drops a column", "ALTER TABLE dbo.veiculo DROP COLUMN legado"),
                spares("DropColumnWarn", "adds a column", "ALTER TABLE dbo.veiculo ADD novo int"),

                flags("UpdateWithoutWhere", "updates every row", "UPDATE veiculo SET ativo = 1"),
                flags("UpdateWithoutWhere", "second statement has no WHERE, the first one's does not cover it",
                        "update a set x = 1 where id = 1", "update b set y = 2"),
                spares("UpdateWithoutWhere", "has a WHERE", "UPDATE veiculo SET ativo = 1 WHERE id = 3"),
                spares("UpdateWithoutWhere", "ON UPDATE CASCADE is a foreign key option, not an UPDATE",
                        "create table t (id int, constraint fk foreign key (id) references p(id) on update cascade)"),
                spares("UpdateWithoutWhere", "FOR UPDATE is a locking clause", "select * from a for update"),

                flags("AddNotNullWithoutDefault", "adds NOT NULL with no default",
                        "ALTER TABLE veiculo ADD pais VARCHAR(2) NOT NULL"),
                flags("AddNotNullWithoutDefault", "turns an existing column NOT NULL",
                        "ALTER TABLE veiculo ALTER COLUMN placa VARCHAR(7) NOT NULL"),
                spares("AddNotNullWithoutDefault", "has a default",
                        "ALTER TABLE veiculo ADD pais VARCHAR(2) NOT NULL DEFAULT 'BR'"),
                spares("AddNotNullWithoutDefault", "is nullable", "ALTER TABLE veiculo ADD pais VARCHAR(2) NULL"),
                spares("AddNotNullWithoutDefault", "a NEW table has no rows to backfill", "CREATE TABLE t (id int NOT NULL)"),

                flags("RenameObjectWarn", "sp_rename", "EXEC sp_rename 'dbo.a', 'b'"),
                flags("RenameObjectWarn", "rename column", "ALTER TABLE a RENAME COLUMN x TO y"),
                flags("RenameObjectWarn", "rename table", "ALTER TABLE a RENAME TO b"),
                spares("RenameObjectWarn", "no rename", "ALTER TABLE a ADD y int"),

                flags("GrantWithGrantOption", "lets the grantee re-grant", "GRANT SELECT ON a TO app WITH GRANT OPTION"),
                spares("GrantWithGrantOption", "plain grant", "GRANT SELECT ON a TO app"),

                flags("CreateLoginOrUser", "creates a login", "CREATE LOGIN app WITH PASSWORD = 'x'"),
                flags("CreateLoginOrUser", "creates a user", "CREATE USER app FOR LOGIN app"),
                spares("CreateLoginOrUser", "a TABLE named users is not a user", "CREATE TABLE users (id int)"),
                spares("CreateLoginOrUser", "a role is not a login or user", "CREATE ROLE reader"),

                flags("DeprecatedDataType", "TEXT column", "CREATE TABLE t (id int, obs TEXT)"),
                flags("DeprecatedDataType", "IMAGE added", "ALTER TABLE t ADD foto IMAGE"),
                spares("DeprecatedDataType", "VARCHAR(MAX) is the replacement", "CREATE TABLE t (id int, obs VARCHAR(MAX))"),
                spares("DeprecatedDataType", "a bracketed column NAMED text", "CREATE TABLE t (id int, [text] nvarchar(10))"),
                spares("DeprecatedDataType", "texto is not text", "CREATE TABLE t (id int, texto nvarchar(10))"),
                spares("DeprecatedDataType", "not a table definition", "select text from t"),

                flags("SqlSelectStarWarn", "view", "CREATE VIEW v AS SELECT * FROM t"),
                flags("SqlSelectStarWarn", "procedure", "ALTER PROCEDURE p AS SELECT * FROM t"),
                spares("SqlSelectStarWarn", "columns listed", "CREATE VIEW v AS SELECT id, nome FROM t"),
                spares("SqlSelectStarWarn", "a plain query is not a view", "SELECT * FROM t"),
                spares("SqlSelectStarWarn", "the star belongs to a LATER statement, not to the view",
                        "create view v as select id from t", "select * from x"),

                flags("NoLockHintWarn", "WITH (NOLOCK)", "SELECT * FROM t WITH (NOLOCK)"),
                flags("NoLockHintWarn", "compact form", "select * from t with(nolock)"),
                flags("NoLockHintWarn", "legacy form", "select * from t (nolock)"),
                spares("NoLockHintWarn", "no hint", "select * from t"),

                flags("PrimaryKeyRequired", "table with no key", "CREATE TABLE t (id int)"),
                spares("PrimaryKeyRequired", "key in the table", "CREATE TABLE t (id int, PRIMARY KEY (id))"),
                spares("PrimaryKeyRequired", "key added in the same changeset",
                        "CREATE TABLE t (id int)", "ALTER TABLE t ADD CONSTRAINT pk PRIMARY KEY (id)"),
                spares("PrimaryKeyRequired", "creates no table", "ALTER TABLE t ADD c int"),

                flags("CheckTablesForIndex", "FK and no index",
                        "CREATE TABLE t (id int PRIMARY KEY, p int, CONSTRAINT fk FOREIGN KEY (p) REFERENCES x(id))"),
                spares("CheckTablesForIndex", "an index is created too",
                        "CREATE TABLE t (id int PRIMARY KEY, p int, CONSTRAINT fk FOREIGN KEY (p) REFERENCES x(id))",
                        "CREATE INDEX ix ON t(p)"),
                spares("CheckTablesForIndex", "no foreign key", "CREATE TABLE t (id int PRIMARY KEY)"),

                flags("ForeignKeyWithoutIndex", "FK added, no index",
                        "ALTER TABLE t ADD CONSTRAINT fk FOREIGN KEY (p) REFERENCES x(id)"),
                spares("ForeignKeyWithoutIndex", "index created in the same changeset",
                        "ALTER TABLE t ADD CONSTRAINT fk FOREIGN KEY (p) REFERENCES x(id)", "CREATE INDEX ix ON t(p)"),
                spares("ForeignKeyWithoutIndex", "FK inside CREATE TABLE is CheckTablesForIndex's business",
                        "CREATE TABLE t (id int, p int, CONSTRAINT fk FOREIGN KEY (p) REFERENCES x(id))"),

                flags("GrantStatementReview", "with no list, every grant is reviewed", "GRANT SELECT ON a TO app"),
                flagsWith("GrantStatementReview", Map.of("tables", "a, b"), "grant on a listed table",
                        "grant select on b to app"),
                sparesWith("GrantStatementReview", Map.of("tables", "a, b"), "grant on a table nobody listed",
                        "grant select on c to app"),
                flagsWith("GrantStatementReview", Map.of("tables", "dbo.veiculo"),
                        "bracketed identifiers compare equal to plain ones", "GRANT SELECT ON [dbo].[veiculo] TO app"),
                spares("GrantStatementReview", "no grant at all", "select 1"));
    }

    @ParameterizedTest(name = "{0}: {4}")
    @MethodSource("cases")
    @DisplayName("each SQL check flags what it should and spares what it should")
    void behaviour(String code, Map<String, Object> params, List<String> statements, boolean flagged, String why) {
        PolicyCheck check = CHECKS.get(code);
        assertThat(check).as("check %s is registered", code).isNotNull();

        ChangeSet changeSet = new ChangeSet("SUP-1-001", "p.ricardo", false, false, "x.xml", "prd", null, CHANGELOG);
        changeSet.addChange(new RawSQLChange());
        CheckContext ctx = new CheckContext(CHANGELOG, List.of(changeSet), new PostgresDatabase(),
                cs -> statements, Optional.empty(), "PRD", true);

        List<Finding> findings = check.evaluate(ctx, params);

        if (flagged) {
            assertThat(findings).as("%s should flag: %s", code, why).hasSize(1);
            assertThat(findings.get(0).checkCode()).isEqualTo(code);
            assertThat(findings.get(0).message()).isNotBlank();
        } else {
            assertThat(findings).as("%s must NOT flag: %s", code, why).isEmpty();
        }
    }
}
