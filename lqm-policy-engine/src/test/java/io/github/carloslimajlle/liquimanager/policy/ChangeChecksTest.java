package io.github.carloslimajlle.liquimanager.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.policy.builtin.ChangeChecks;
import liquibase.Labels;
import liquibase.change.AddColumnConfig;
import liquibase.change.ColumnConfig;
import liquibase.change.ConstraintsConfig;
import liquibase.change.core.AddColumnChange;
import liquibase.change.core.AddForeignKeyConstraintChange;
import liquibase.change.core.AddPrimaryKeyChange;
import liquibase.change.core.CreateIndexChange;
import liquibase.change.core.CreateTableChange;
import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.database.core.PostgresDatabase;

/**
 * The phase-1 checks that read the changeset's MODEL. Same discipline as the SQL ones:
 * for each, what it must flag and what it must leave alone.
 */
class ChangeChecksTest {

    private static final DatabaseChangeLog CHANGELOG = new DatabaseChangeLog("master.xml");

    private static ChangeSet changeSet(String author) {
        return new ChangeSet("SUP-1-001", author, false, false, "x.xml", "prd", null, CHANGELOG);
    }

    private static List<Finding> run(PolicyCheck check, ChangeSet changeSet, Map<String, Object> params) {
        CheckContext ctx = new CheckContext(CHANGELOG, List.of(changeSet), new PostgresDatabase(),
                cs -> List.of(), Optional.empty(), "PRD", true);
        return check.evaluate(ctx, params);
    }

    private static List<Finding> run(PolicyCheck check, ChangeSet changeSet) {
        return run(check, changeSet, Map.of());
    }

    private static CreateTableChange table(String name, String... columns) {
        CreateTableChange create = new CreateTableChange();
        create.setTableName(name);
        for (String column : columns) {
            create.addColumn(new ColumnConfig().setName(column).setType("int"));
        }
        return create;
    }

    @Nested
    @DisplayName("AuthorRequired / AuthorAllowedList")
    class Authors {

        @Test
        @DisplayName("an empty author is flagged, a named one is not")
        void emptyAuthor() {
            assertThat(run(new ChangeChecks.AuthorRequired(), changeSet(""))).hasSize(1);
            assertThat(run(new ChangeChecks.AuthorRequired(), changeSet("p.ricardo"))).isEmpty();
        }

        @Test
        @DisplayName("an author outside the list is flagged; the comparison ignores case")
        void outsideList() {
            Map<String, Object> params = Map.of("allowed", "p.ricardo, l.martins");

            assertThat(run(new ChangeChecks.AuthorAllowedList(), changeSet("intruso"), params)).hasSize(1);
            assertThat(run(new ChangeChecks.AuthorAllowedList(), changeSet("P.Ricardo"), params)).isEmpty();
        }

        @Test
        @DisplayName("with no list configured the check stays quiet instead of rejecting everyone")
        void noListIsQuiet() {
            assertThat(run(new ChangeChecks.AuthorAllowedList(), changeSet("qualquer"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("LabelRequired")
    class ReleaseLabel {

        @Test
        @DisplayName("flags a changeset with no release label, including one with only other labels")
        void missing() {
            ChangeSet none = changeSet("p.ricardo");
            ChangeSet other = changeSet("p.ricardo");
            other.setLabels(new Labels("hotfix"));

            assertThat(run(new ChangeChecks.LabelRequired(), none)).hasSize(1);
            assertThat(run(new ChangeChecks.LabelRequired(), other)).hasSize(1);
        }

        @Test
        @DisplayName("accepts a release label, even among others, and honours a custom prefix")
        void present() {
            ChangeSet release = changeSet("p.ricardo");
            release.setLabels(new Labels("hotfix,release-2.1.0"));

            assertThat(run(new ChangeChecks.LabelRequired(), release)).isEmpty();
            assertThat(run(new ChangeChecks.LabelRequired(), release, Map.of("prefix", "sprint-"))).hasSize(1);
        }
    }

    @Nested
    @DisplayName("SchemaRequired")
    class Schema {

        @Test
        @DisplayName("flags a change that names no schema, and accepts one that does")
        void schema() {
            ChangeSet without = changeSet("p.ricardo");
            without.addChange(table("veiculo", "id"));
            ChangeSet with = changeSet("p.ricardo");
            CreateTableChange named = table("veiculo", "id");
            named.setSchemaName("dbo");
            with.addChange(named);

            assertThat(run(new ChangeChecks.SchemaRequired(), without)).hasSize(1);
            assertThat(run(new ChangeChecks.SchemaRequired(), with)).isEmpty();
        }

        @Test
        @DisplayName("a change with no schemaName parameter at all (raw SQL) is not accused")
        void rawSqlHasNoSchemaParameter() {
            ChangeSet raw = changeSet("p.ricardo");
            raw.addChange(new RawSQLChange());

            assertThat(run(new ChangeChecks.SchemaRequired(), raw)).isEmpty();
        }
    }

    @Nested
    @DisplayName("DbmsAttributeRequiredForRawSql")
    class Dbms {

        @Test
        @DisplayName("flags raw SQL with no dbms, accepts it with one, and ignores structured changes")
        void dbms() {
            ChangeSet without = changeSet("p.ricardo");
            without.addChange(new RawSQLChange());

            ChangeSet with = changeSet("p.ricardo");
            RawSQLChange sql = new RawSQLChange();
            sql.setDbms("mssql");
            with.addChange(sql);

            ChangeSet structured = changeSet("p.ricardo");
            structured.addChange(table("veiculo", "id"));

            assertThat(run(new ChangeChecks.DbmsAttributeRequiredForRawSql(), without)).hasSize(1);
            assertThat(run(new ChangeChecks.DbmsAttributeRequiredForRawSql(), with)).isEmpty();
            assertThat(run(new ChangeChecks.DbmsAttributeRequiredForRawSql(), structured)).isEmpty();
        }
    }

    @Nested
    @DisplayName("TableColumnLimit")
    class ColumnLimit {

        @Test
        @DisplayName("flags a table over the limit, honours the max parameter, defaults to 50")
        void limit() {
            ChangeSet wide = changeSet("p.ricardo");
            wide.addChange(table("larga", "a", "b", "c"));

            assertThat(run(new ChangeChecks.TableColumnLimit(), wide, Map.of("max", 2))).hasSize(1);
            assertThat(run(new ChangeChecks.TableColumnLimit(), wide, Map.of("max", 3))).isEmpty();
            assertThat(run(new ChangeChecks.TableColumnLimit(), wide)).as("3 columns is far below 50").isEmpty();
        }
    }

    @Nested
    @DisplayName("ObjectNamingConvention")
    class Naming {

        private ChangeSet with(liquibase.change.Change change) {
            ChangeSet changeSet = changeSet("p.ricardo");
            changeSet.addChange(change);
            return changeSet;
        }

        @Test
        @DisplayName("primary key, foreign key and index names must carry their prefix")
        void prefixes() {
            AddPrimaryKeyChange pk = new AddPrimaryKeyChange();
            pk.setConstraintName("veiculo_pk");
            AddForeignKeyConstraintChange fk = new AddForeignKeyConstraintChange();
            fk.setConstraintName("agendamento_veiculo");
            CreateIndexChange ix = new CreateIndexChange();
            ix.setIndexName("idx_veiculo_placa");

            assertThat(run(new ChangeChecks.ObjectNamingConvention(), with(pk))).hasSize(1);
            assertThat(run(new ChangeChecks.ObjectNamingConvention(), with(fk))).hasSize(1);
            assertThat(run(new ChangeChecks.ObjectNamingConvention(), with(ix))).hasSize(1);
        }

        @Test
        @DisplayName("correctly prefixed names pass, the comparison ignores case, and unnamed ones are not this check's business")
        void conforming() {
            AddPrimaryKeyChange pk = new AddPrimaryKeyChange();
            pk.setConstraintName("PK_veiculo");
            CreateIndexChange ix = new CreateIndexChange();
            ix.setIndexName("ix_veiculo_placa");
            AddForeignKeyConstraintChange unnamed = new AddForeignKeyConstraintChange();

            assertThat(run(new ChangeChecks.ObjectNamingConvention(), with(pk))).isEmpty();
            assertThat(run(new ChangeChecks.ObjectNamingConvention(), with(ix))).isEmpty();
            assertThat(run(new ChangeChecks.ObjectNamingConvention(), with(unnamed))).isEmpty();
        }

        @Test
        @DisplayName("constraints declared on the columns of a new table are checked too")
        void inlineConstraints() {
            CreateTableChange create = new CreateTableChange();
            create.setTableName("veiculo");
            create.addColumn(new ColumnConfig().setName("id").setType("int")
                    .setConstraints(new ConstraintsConfig().setPrimaryKey(true).setPrimaryKeyName("veiculo_pk")));

            assertThat(run(new ChangeChecks.ObjectNamingConvention(), with(create))).hasSize(1);
        }

        @Test
        @DisplayName("a prefix can be changed through a parameter")
        void customPrefix() {
            CreateIndexChange ix = new CreateIndexChange();
            ix.setIndexName("idx_veiculo_placa");

            assertThat(run(new ChangeChecks.ObjectNamingConvention(), with(ix), Map.of("ix", "idx_"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("TableNamePattern / ColumnNamePattern")
    class NamePatterns {

        @Test
        @DisplayName("table names must be snake_case by default, and the pattern is configurable")
        void tables() {
            ChangeSet bad = changeSet("p.ricardo");
            bad.addChange(table("Veiculo", "id"));
            ChangeSet good = changeSet("p.ricardo");
            good.addChange(table("veiculo_agendado", "id"));

            assertThat(run(new ChangeChecks.TableNamePattern(), bad)).hasSize(1);
            assertThat(run(new ChangeChecks.TableNamePattern(), good)).isEmpty();
            assertThat(run(new ChangeChecks.TableNamePattern(), bad, Map.of("pattern", "^[A-Z][a-z]+$"))).isEmpty();
        }

        @Test
        @DisplayName("column names are judged in new tables and in added columns")
        void columns() {
            ChangeSet inTable = changeSet("p.ricardo");
            inTable.addChange(table("veiculo", "id", "CNH"));

            ChangeSet added = changeSet("p.ricardo");
            AddColumnChange add = new AddColumnChange();
            AddColumnConfig column = new AddColumnConfig();
            column.setName("NumeroDoc");
            column.setType("int");
            add.addColumn(column);
            added.addChange(add);

            assertThat(run(new ChangeChecks.ColumnNamePattern(), inTable)).hasSize(1);
            assertThat(run(new ChangeChecks.ColumnNamePattern(), added)).hasSize(1);
        }

        @Test
        @DisplayName("an invalid regex never breaks the evaluation")
        void invalidRegex() {
            ChangeSet changeSet = changeSet("p.ricardo");
            changeSet.addChange(table("Veiculo", "id"));

            assertThat(run(new ChangeChecks.TableNamePattern(), changeSet, Map.of("pattern", "[unclosed"))).isEmpty();
        }
    }
}
