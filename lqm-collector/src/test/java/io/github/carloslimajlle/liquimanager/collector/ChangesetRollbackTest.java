package io.github.carloslimajlle.liquimanager.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * The three Liquibase internals behind {@code rollback-one-changeset} (ChangeSet.rollback, removeFromHistory, the
 * lock service), run for real on H2. If a Liquibase upgrade changes any of them, this is where it shows.
 */
class ChangesetRollbackTest {

    private final Connection connection;
    private final Liquibase liquibase;

    ChangesetRollbackTest() throws Exception {
        connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
        liquibase = new Liquibase("db/changelog.xml", new ClassLoaderResourceAccessor(), database);
        liquibase.update(new Contexts("app,seed"), new LabelExpression());
    }

    private boolean tableExists(String name) throws SQLException {
        try (ResultSet rs = connection.getMetaData().getTables(null, null, name.toUpperCase(), null)) {
            return rs.next();
        }
    }

    private long history(String id) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("select count(*) from DATABASECHANGELOG where ID = '" + id + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    @DisplayName("reverts one changeset and takes it out of DATABASECHANGELOG")
    void revertsAndForgets() throws Exception {
        // SUP-1-002 inserts a row and has an explicit rollback; it is not the last one applied
        ChangeSetRecorder recorder = new ChangeSetRecorder();
        assertThat(history("SUP-1-002-seed-only-dev")).isEqualTo(1);

        ChangesetRollback.run(liquibase, "SUP-1-002-seed-only-dev", "p.ricardo", null, recorder);

        assertThat(history("SUP-1-002-seed-only-dev")).isZero();
        assertThat(history("SUP-1-001-create-cliente")).as("the others stay").isEqualTo(1);
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("select count(*) from cliente")) {
            rs.next();
            assertThat(rs.getLong(1)).as("its insert was reverted").isZero();
        }
        assertThat(recorder.changesets()).extracting("outcome").contains("ROLLED_BACK");
    }

    @Test
    @DisplayName("a changeset that can be applied again after it was reverted")
    void canBeAppliedAgain() throws Exception {
        ChangesetRollback.run(liquibase, "SUP-1-001-create-cliente", "p.ricardo", null, new ChangeSetRecorder());
        // SUP-1-002 (the insert) now has no table; the point is the table is gone and history forgot the changeset
        assertThat(tableExists("cliente")).isFalse();
        assertThat(history("SUP-1-001-create-cliente")).isZero();
    }

    @Test
    @DisplayName("refuses a changeset that is not in the changelog")
    void unknownChangeset() {
        assertThatThrownBy(() -> ChangesetRollback.run(liquibase, "NOPE", null, null, new ChangeSetRecorder()))
                .isInstanceOf(ChangesetRollback.Refused.class).hasMessageContaining("not in the changelog");
    }

    @Test
    @DisplayName("refuses one that has nothing to revert it with, and changes nothing")
    void noRollback() throws Exception {
        assertThatThrownBy(() -> ChangesetRollback.run(liquibase, "SUP-1-003-raw-no-rollback", "p.ricardo", null, new ChangeSetRecorder()))
                .isInstanceOf(ChangesetRollback.Refused.class).hasMessageContaining("no rollback");
        assertThat(history("SUP-1-003-raw-no-rollback")).as("still applied").isEqualTo(1);
    }

    @Test
    @DisplayName("refuses one that was never applied here")
    void neverApplied() throws Exception {
        // the "seed" context was applied, but this run asks for a changeset that was filtered out of a fresh database
        Connection other = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(other));
        Liquibase fresh = new Liquibase("db/changelog.xml", new ClassLoaderResourceAccessor(), database);
        fresh.update(new Contexts("app"), new LabelExpression());

        assertThatThrownBy(() -> ChangesetRollback.run(fresh, "SUP-1-002-seed-only-dev", "p.ricardo", null, new ChangeSetRecorder()))
                .isInstanceOf(ChangesetRollback.Refused.class).hasMessageContaining("never applied");
    }

    @Test
    @DisplayName("the author narrows the match; a wrong author finds nothing")
    void authorMustMatch() {
        assertThatThrownBy(() -> ChangesetRollback.run(liquibase, "SUP-1-002-seed-only-dev", "someone.else", null, new ChangeSetRecorder()))
                .isInstanceOf(ChangesetRollback.Refused.class);
    }
}
