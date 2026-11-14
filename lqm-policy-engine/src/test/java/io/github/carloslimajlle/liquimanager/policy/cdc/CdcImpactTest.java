package io.github.carloslimajlle.liquimanager.policy.cdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.carloslimajlle.liquimanager.policy.SqlGeneration;
import io.github.carloslimajlle.liquimanager.policy.cdc.CdcImpact.Impact;
import io.github.carloslimajlle.liquimanager.policy.cdc.CdcImpact.Kind;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.DirectoryResourceAccessor;

/** What a changeset does to a table that CDC tracks: the changes that stop the capture, the ones that only leave a column out, and the ones that are not about CDC at all. */
class CdcImpactTest {

    private static final List<CdcTable> TRACKED = List.of(
            new CdcTable("dbo", "Cliente", List.of(new CdcTable.Instance("dbo_Cliente", null, true))),
            new CdcTable("sales", "Pedido", List.of(new CdcTable.Instance("sales_Pedido", "cdc_reader", false))));

    private static List<Impact> of(String... statements) {
        return CdcImpact.of(List.of(statements), TRACKED);
    }

    @Test
    @DisplayName("retyping, dropping and renaming a column, and renaming or dropping the table, are the changes that break the capture")
    void breaking() {
        assertThat(of("ALTER TABLE [dbo].[Cliente] ALTER COLUMN [nome] VARCHAR(300) NOT NULL"))
                .containsExactly(new Impact("dbo.Cliente", Kind.COLUMN_ALTERED, "nome"));
        assertThat(of("ALTER TABLE [dbo].[Cliente] DROP COLUMN [telefone]"))
                .containsExactly(new Impact("dbo.Cliente", Kind.COLUMN_DROPPED, "telefone"));
        assertThat(of("exec sp_rename '[dbo].[Cliente].[nome]', 'razao', 'COLUMN'"))
                .containsExactly(new Impact("dbo.Cliente", Kind.COLUMN_RENAMED, "nome -> razao"));
        assertThat(of("exec sp_rename '[dbo].[Cliente]', 'Cliente2'"))
                .containsExactly(new Impact("dbo.Cliente", Kind.TABLE_RENAMED, "Cliente2"));
        assertThat(of("DROP TABLE [dbo].[Cliente]")).containsExactly(new Impact("dbo.Cliente", Kind.TABLE_DROPPED, null));
        assertThat(Kind.values()).extracting(Kind::breaking).containsExactly(false, true, true, true, true, true);
    }

    @Test
    @DisplayName("a new column breaks nothing: it is only not captured until the instance is created again")
    void addedColumn() {
        List<Impact> impacts = of("ALTER TABLE [dbo].[Cliente] ADD [email] VARCHAR(180) NULL");

        assertThat(impacts).containsExactly(new Impact("dbo.Cliente", Kind.COLUMN_ADDED, "email"));
        assertThat(Kind.COLUMN_ADDED.breaking()).isFalse();
        assertThat(Kind.COLUMN_ADDED.needsRecapture()).isTrue();
        assertThat(Kind.TABLE_DROPPED.needsRecapture()).as("nothing to create again when the table is gone").isFalse();
    }

    @Test
    @DisplayName("adding a constraint, an index or a key is not adding a column")
    void notAColumn() {
        assertThat(of("ALTER TABLE [dbo].[Cliente] ADD CONSTRAINT [pk_cliente] PRIMARY KEY ([id])")).isEmpty();
        assertThat(of("ALTER TABLE [dbo].[Cliente] ADD PRIMARY KEY ([id])")).isEmpty();
        assertThat(of("ALTER TABLE [dbo].[Cliente] ADD FOREIGN KEY ([x]) REFERENCES [dbo].[Y] ([id])")).isEmpty();
        assertThat(of("ALTER TABLE [dbo].[Cliente] ADD DEFAULT 0 FOR [x]")).isEmpty();
        assertThat(of("CREATE INDEX ix ON [dbo].[Cliente] ([nome])")).isEmpty();
    }

    @Test
    @DisplayName("only tables that CDC tracks count, matched without regard to case, and the schema defaults to dbo")
    void trackedOnly() {
        assertThat(of("ALTER TABLE [dbo].[Fornecedor] ALTER COLUMN [nome] VARCHAR(300)")).isEmpty();
        assertThat(of("alter table cliente alter column nome varchar(300)")).containsExactly(new Impact("dbo.Cliente", Kind.COLUMN_ALTERED, "nome"));
        assertThat(of("ALTER TABLE sales.pedido DROP COLUMN obs")).containsExactly(new Impact("sales.Pedido", Kind.COLUMN_DROPPED, "obs"));
        assertThat(of("ALTER TABLE [other].[Cliente] DROP COLUMN [x]")).as("same name, another schema").isEmpty();
        assertThat(CdcImpact.of(List.of("ALTER TABLE [dbo].[Cliente] DROP COLUMN [x]"), List.of())).as("no CDC where it is going").isEmpty();
    }

    @Test
    @DisplayName("several columns in one statement, the same impact twice and a database prefix")
    void shapes() {
        assertThat(of("ALTER TABLE [dbo].[Cliente] DROP COLUMN [a], [b]"))
                .containsExactly(new Impact("dbo.Cliente", Kind.COLUMN_DROPPED, "a"), new Impact("dbo.Cliente", Kind.COLUMN_DROPPED, "b"));
        assertThat(of("ALTER TABLE [dbo].[Cliente] DROP COLUMN [a]", "ALTER TABLE [dbo].[Cliente] DROP COLUMN [a]")).hasSize(1);
        assertThat(of("ALTER TABLE [Core].[dbo].[Cliente] ALTER COLUMN [nome] VARCHAR(10)"))
                .containsExactly(new Impact("dbo.Cliente", Kind.COLUMN_ALTERED, "nome"));
    }

    @Test
    @DisplayName("qualified names are split on the dots outside brackets")
    void names() {
        assertThat(CdcImpact.parts("[dbo].[Cliente]")).containsExactly("dbo", "Cliente");
        assertThat(CdcImpact.parts("[my.schema].[Cli.ente]")).containsExactly("my.schema", "Cli.ente");
        assertThat(CdcImpact.parts("\"dbo\".\"Cliente\"")).containsExactly("dbo", "Cliente");
        assertThat(CdcImpact.parts("Cliente")).containsExactly("Cliente");
    }

    @Test
    @DisplayName("the SQL Liquibase really generates for SQL Server is the SQL that is recognised")
    void withRealLiquibaseOutput(@TempDir Path dir) throws Exception {
        String xml = String.join("\n",
                "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"",
                "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
                "    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd\">",
                "  <changeSet id=\"1\" author=\"t\"><addColumn tableName=\"Cliente\"><column name=\"email\" type=\"varchar(180)\"/></addColumn></changeSet>",
                "  <changeSet id=\"2\" author=\"t\"><modifyDataType tableName=\"Cliente\" columnName=\"nome\" newDataType=\"varchar(300)\"/></changeSet>",
                "  <changeSet id=\"3\" author=\"t\"><dropColumn tableName=\"Cliente\" columnName=\"telefone\"/></changeSet>",
                "  <changeSet id=\"4\" author=\"t\"><renameColumn tableName=\"Cliente\" oldColumnName=\"nome\" newColumnName=\"razao\" columnDataType=\"varchar(300)\"/></changeSet>",
                "  <changeSet id=\"5\" author=\"t\"><renameTable oldTableName=\"Cliente\" newTableName=\"Cliente2\"/></changeSet>",
                "  <changeSet id=\"6\" author=\"t\"><dropTable tableName=\"Cliente\"/></changeSet>",
                "</databaseChangeLog>");
        Files.writeString(dir.resolve("c.xml"), xml, StandardCharsets.UTF_8);
        DirectoryResourceAccessor accessor = new DirectoryResourceAccessor(dir);
        Database database = DatabaseFactory.getInstance().openDatabase("offline:mssql", null, null, null, null, null, null, accessor);
        List<ChangeSet> changeSets = new ArrayList<>(ChangeLogParserFactory.getInstance().getParser("c.xml", accessor)
                .parse("c.xml", new ChangeLogParameters(database), accessor).getChangeSets());

        List<Kind> kinds = new ArrayList<>();
        for (ChangeSet changeSet : changeSets) {
            List<Impact> impacts = CdcImpact.of(SqlGeneration.of(changeSet, database), TRACKED);
            assertThat(impacts).as("changeset %s: %s", changeSet.getId(), SqlGeneration.of(changeSet, database)).hasSize(1);
            kinds.add(impacts.get(0).kind());
        }
        assertThat(kinds).containsExactly(Kind.COLUMN_ADDED, Kind.COLUMN_ALTERED, Kind.COLUMN_DROPPED, Kind.COLUMN_RENAMED, Kind.TABLE_RENAMED, Kind.TABLE_DROPPED);
    }
}
