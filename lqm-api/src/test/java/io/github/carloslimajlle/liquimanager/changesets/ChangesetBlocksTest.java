package io.github.carloslimajlle.liquimanager.changesets;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Where one changeset sits in a changelog file; unsure means no answer, so a wrong block is never shown before an approval. */
class ChangesetBlocksTest {

    private static final String XML = String.join("\n",
            "<databaseChangeLog>",
            "  <changeSet id=\"A-1\" author=\"ana\">",
            "    <createTable tableName=\"cliente\"/>",
            "    <rollback><dropTable tableName=\"cliente\"/></rollback>",
            "  </changeSet>",
            "  <changeSet author=\"ana\"",
            "             id=\"A-2\">",
            "    <addColumn tableName=\"cliente\"/>",
            "  </changeSet>",
            "  <changeSet id=\"A-3\" author=\"bia\"/>",
            "</databaseChangeLog>");

    private static final String YAML = String.join("\n",
            "databaseChangeLog:",
            "  - changeSet:",
            "      id: A-1",
            "      author: ana",
            "      changes:",
            "        - createTable:",
            "            tableName: cliente",
            "      rollback:",
            "        - dropTable:",
            "            tableName: cliente",
            "",
            "  - changeSet:",
            "      id: \"A-2\"",
            "      author: 'bia'",
            "      changes:",
            "        - addColumn:",
            "            tableName: cliente");

    private static final String SQL = String.join("\n",
            "--liquibase formatted sql",
            "",
            "--changeset ana:A-1",
            "create table cliente (id int);",
            "--rollback drop table cliente;",
            "",
            "--changeset bia:A-2",
            "alter table cliente add nome text;");

    private static String lines(String text, ChangesetBlocks.Range range) {
        String[] all = text.split("\n", -1);
        return String.join("\n", java.util.Arrays.copyOfRange(all, range.start() - 1, range.end()));
    }

    @Test
    @DisplayName("XML: the element with that id and author, with its rollback, in whatever order the attributes are")
    void xml() {
        var first = ChangesetBlocks.find(XML, "db/c.xml", "A-1", "ana").orElseThrow();
        assertThat(first).isEqualTo(new ChangesetBlocks.Range(2, 5));
        assertThat(lines(XML, first)).contains("createTable").contains("rollback").doesNotContain("A-2");

        var second = ChangesetBlocks.find(XML, "db/c.xml", "A-2", "ana").orElseThrow();
        assertThat(second).isEqualTo(new ChangesetBlocks.Range(6, 9));

        assertThat(ChangesetBlocks.find(XML, "db/c.xml", "A-3", "bia").orElseThrow()).isEqualTo(new ChangesetBlocks.Range(10, 10));
    }

    @Test
    @DisplayName("YAML: the entry up to the next one, matching id and author, quoted or not")
    void yaml() {
        var first = ChangesetBlocks.find(YAML, "db/c.yaml", "A-1", "ana").orElseThrow();
        assertThat(first).isEqualTo(new ChangesetBlocks.Range(2, 10));
        assertThat(lines(YAML, first)).contains("dropTable").doesNotContain("A-2");

        var second = ChangesetBlocks.find(YAML, "db/c.yml", "A-2", "bia").orElseThrow();
        assertThat(second).isEqualTo(new ChangesetBlocks.Range(12, 17));
    }

    @Test
    @DisplayName("formatted SQL: from its --changeset line to the next one, without the blank line before it")
    void sql() {
        var first = ChangesetBlocks.find(SQL, "db/c.sql", "A-1", "ana").orElseThrow();
        assertThat(first).isEqualTo(new ChangesetBlocks.Range(3, 5));

        var last = ChangesetBlocks.find(SQL, "db/c.sql", "A-2", "bia").orElseThrow();
        assertThat(last).isEqualTo(new ChangesetBlocks.Range(7, 8));
    }

    @Test
    @DisplayName("the same id by another author, an unknown id, or a format it does not read has no block")
    void unsure() {
        assertThat(ChangesetBlocks.find(XML, "db/c.xml", "A-1", "bia")).isEmpty();
        assertThat(ChangesetBlocks.find(XML, "db/c.xml", "A-9", "ana")).isEmpty();
        assertThat(ChangesetBlocks.find(YAML, "db/c.yaml", "A-1", "bia")).isEmpty();
        assertThat(ChangesetBlocks.find(SQL, "db/c.sql", "A-1", "bia")).isEmpty();
        assertThat(ChangesetBlocks.find("{}", "db/c.json", "A-1", "ana")).isEmpty();
        assertThat(ChangesetBlocks.find(null, "db/c.xml", "A-1", "ana")).isEmpty();
    }
}
