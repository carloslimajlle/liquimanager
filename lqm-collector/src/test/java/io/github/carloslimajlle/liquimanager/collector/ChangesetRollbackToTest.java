package io.github.carloslimajlle.liquimanager.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * "Go back to a changeset": it undoes that changeset and everything applied after it, newest first, never one in the middle.
 * Run for real on H2 with three changesets that stand on each other (table, column, row).
 */
class ChangesetRollbackToTest {

    private static final String FILE = "db/changelog-chain.xml";
    private static final String A = "A-create-pedido::ana::" + FILE;
    private static final String B = "B-add-total::ana::" + FILE;
    private static final String C = "C-seed::bruno::" + FILE;

    private final Connection connection;
    private final Liquibase liquibase;

    ChangesetRollbackToTest() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        liquibase = new Liquibase(FILE, new ClassLoaderResourceAccessor(), database);
        liquibase.update(new Contexts(), new LabelExpression());
    }

    private boolean tableExists(String name) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, name.toUpperCase(), null)) {
            return rs.next();
        }
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, table.toUpperCase(), column.toUpperCase())) {
            return rs.next();
        }
    }

    private long history() throws SQLException {
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("select count(*) from DATABASECHANGELOG")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("the plan is the changeset and everything after it, newest first")
    void planIsTheTailNewestFirst() throws Exception {
        assertThat(ChangesetRollback.planTo(liquibase, "A-create-pedido", null, null)).containsExactly(C, B, A);
        assertThat(ChangesetRollback.planTo(liquibase, "B-add-total", null, null)).containsExactly(C, B);
        assertThat(ChangesetRollback.planTo(liquibase, "C-seed", null, null)).containsExactly(C);
        assertThat(history()).as("planning changes nothing").isEqualTo(3);
    }

    @Test
    @DisplayName("going back to the middle undoes it and what came after, and keeps what came before")
    void goesBackToTheMiddle() throws Exception {
        ChangeSetRecorder recorder = new ChangeSetRecorder();
        liquibase.setChangeExecListener(recorder);

        List<String> undone = ChangesetRollback.runTo(liquibase, "B-add-total", null, null, List.of(C, B), new Contexts(), new LabelExpression());

        assertThat(undone).containsExactly(C, B);
        assertThat(tableExists("pedido")).as("A stays").isTrue();
        assertThat(columnExists("pedido", "total")).as("B's column is gone").isFalse();
        assertThat(history()).isEqualTo(1);
        assertThat(recorder.changesets()).extracting("outcome").containsOnly("ROLLED_BACK");
    }

    @Test
    @DisplayName("going back to the first undoes the whole chain, table included")
    void goesBackToTheFirst() throws Exception {
        ChangesetRollback.runTo(liquibase, "A-create-pedido", null, null, null, new Contexts(), new LabelExpression());
        assertThat(tableExists("pedido")).isFalse();
        assertThat(history()).isZero();
    }

    @Test
    @DisplayName("a database that moved since the request is refused, and nothing is undone")
    void refusesWhenTheDatabaseMoved() throws Exception {
        // the person saw "C and B"; since then C was undone by someone else, so B alone is what would go
        ChangesetRollback.runTo(liquibase, "C-seed", null, null, null, new Contexts(), new LabelExpression());

        assertThatThrownBy(() -> ChangesetRollback.runTo(liquibase, "B-add-total", null, null, List.of(C, B), new Contexts(), new LabelExpression()))
                .isInstanceOf(ChangesetRollback.Refused.class).hasMessageContaining("changed since");
        assertThat(columnExists("pedido", "total")).isTrue();
        assertThat(history()).isEqualTo(2);
    }

    @Test
    @DisplayName("a changeset that was never applied cannot be gone back to")
    void neverApplied() throws Exception {
        ChangesetRollback.runTo(liquibase, "A-create-pedido", null, null, null, new Contexts(), new LabelExpression());
        assertThatThrownBy(() -> ChangesetRollback.planTo(liquibase, "B-add-total", null, null))
                .isInstanceOf(ChangesetRollback.Refused.class).hasMessageContaining("never applied");
    }
}
