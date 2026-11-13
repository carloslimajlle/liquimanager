package io.github.carloslimajlle.liquimanager.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.changesets.ChangesetSqlGenerator.Generated;
import io.github.carloslimajlle.liquimanager.changesets.ChangesetSqlGenerator.Target;

/** The SQL of a changeset is generated offline, for the dialect asked, and a changeset that cannot be generated says why instead of vanishing. */
class ChangesetSqlGeneratorTest {

    private static final String XML = String.join("\n",
            "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"",
            "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
            "    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd\">",
            "  <changeSet id=\"A-1\" author=\"ana\">",
            "    <createTable tableName=\"cliente\"><column name=\"id\" type=\"int\"/></createTable>",
            "  </changeSet>",
            "  <changeSet id=\"A-2\" author=\"ana\">",
            "    <sql>update cliente set id = 1</sql>",
            "  </changeSet>",
            "  <changeSet id=\"A-3\" author=\"ana\">",
            "    <sqlFile path=\"elsewhere.sql\"/>",
            "  </changeSet>",
            "</databaseChangeLog>");

    private final ChangesetSqlGenerator generator = new ChangesetSqlGenerator();

    @Test
    @DisplayName("generates the chosen changesets in the dialect asked, and only those")
    void generates() {
        Target first = new Target("A-1", "ana");
        Target second = new Target("A-2", "ana");
        Map<Target, Generated> postgres = generator.generate("db/changelog/work/a.xml", XML, "postgresql", List.of(second, first));

        assertThat(postgres.keySet()).containsExactly(second, first);
        assertThat(postgres.get(first).warning()).isNull();
        assertThat(String.join("\n", postgres.get(first).statements())).containsIgnoringCase("create table").contains("cliente");
        assertThat(postgres.get(second).statements()).anyMatch(s -> s.contains("update cliente"));

        Map<Target, Generated> mssql = generator.generate("db/changelog/work/a.xml", XML, "mssql", List.of(first));
        assertThat(String.join("\n", mssql.get(first).statements())).containsIgnoringCase("create table");
    }

    @Test
    @DisplayName("a changeset that is not in the file, or that reads another file, comes back with a warning")
    void warnings() {
        Target missing = new Target("Z-9", "ana");
        Target other = new Target("A-3", "ana");
        Map<Target, Generated> out = generator.generate("a.xml", XML, "postgresql", List.of(missing, other));

        assertThat(out.get(missing).warning()).isEqualTo("NOT_IN_FILE");
        assertThat(out.get(missing).statements()).isEmpty();
        assertThat(out.get(other).warning()).isEqualTo("NO_SQL");
    }

    @Test
    @DisplayName("a file that does not parse warns for every changeset and does not throw")
    void unparseable() {
        Target target = new Target("A-1", "ana");
        Map<Target, Generated> out = generator.generate("a.xml", "this is not a changelog", "postgresql", List.of(target));

        assertThat(out.get(target).warning()).isEqualTo("UNPARSEABLE");
    }

    @Test
    @DisplayName("a path that climbs out of the work area is refused, not written")
    void pathEscape() {
        Target target = new Target("A-1", "ana");
        Map<Target, Generated> out = generator.generate("../../escape.xml", XML, "postgresql", List.of(target));

        assertThat(out.get(target).warning()).isEqualTo("UNPARSEABLE");
    }

    @Test
    @DisplayName("the dialect follows the engine of the connection")
    void dialects() {
        assertThat(ChangesetSqlGenerator.dialectOf("SQLSERVER")).isEqualTo("mssql");
        assertThat(ChangesetSqlGenerator.dialectOf("POSTGRESQL")).isEqualTo("postgresql");
        assertThat(ChangesetSqlGenerator.dialectOf("ORACLE")).isEqualTo("oracle");
        assertThat(ChangesetSqlGenerator.dialectOf("MYSQL")).isEqualTo("mysql");
        assertThat(ChangesetSqlGenerator.dialectOf("DB2")).isNull();
        assertThat(ChangesetSqlGenerator.dialectOf(null)).isNull();
    }
}
