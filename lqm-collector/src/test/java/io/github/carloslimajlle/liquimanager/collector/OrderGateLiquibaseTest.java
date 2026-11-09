package io.github.carloslimajlle.liquimanager.collector;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.carloslimajlle.liquimanager.client.IngestPayload;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.resource.DirectoryResourceAccessor;

/** {@code validCheckSum} read from a real changelog (YAML; formatted SQL has no way to declare it): the test the deploy uses to let an edited-but-equivalent changeset through the order gate. */
class OrderGateLiquibaseTest {

    @TempDir
    Path dir;

    private static IngestPayload.Changeset changeset(String file) {
        return new IngestPayload.Changeset("SUP-1", "ana", file, "9:current", null, null, null, "NOT_EXECUTED", null, null, null, null, null);
    }

    private Liquibase open(String file, String text) throws Exception {
        Files.writeString(dir.resolve(file), text);
        Database database = DatabaseFactory.getInstance().openDatabase("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "",
                null, null, null, null, new DirectoryResourceAccessor(dir));
        return new Liquibase(file, new DirectoryResourceAccessor(dir), database);
    }

    @Test
    @DisplayName("a YAML changeset that declares the checksum DEV ran as valid accepts it; one that does not, does not")
    void yaml() throws Exception {
        String declared = """
                databaseChangeLog:
                  - changeSet:
                      id: SUP-1
                      author: ana
                      validCheckSum: 9:oldversion
                      changes:
                        - createTable:
                            tableName: t
                            columns:
                              - column: { name: id, type: INT }
                """;
        var accepts = OrderGate.declaredValid(open("declared.yaml", declared), new Contexts(), new LabelExpression());
        assertThat(accepts.test(changeset("declared.yaml"), "9:oldversion")).isTrue();
        assertThat(accepts.test(changeset("declared.yaml"), "9:another")).as("only the declared checksum").isFalse();

        var plain = OrderGate.declaredValid(open("plain.yaml", declared.replace("      validCheckSum: 9:oldversion\n", "")), new Contexts(), new LabelExpression());
        assertThat(plain.test(changeset("plain.yaml"), "9:oldversion")).isFalse();
    }
}
