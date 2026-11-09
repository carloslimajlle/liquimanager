package io.github.carloslimajlle.liquimanager;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * LiquiManager applies to itself the rule it enforces on others (RollbackRequired): every changeset of its own schema
 * reverts. This runs {@code update, rollback of everything, update} on an empty database made for the occasion, so a
 * changeset whose rollback is missing, wrong or incomplete breaks the build instead of a future rollback.
 *
 * <p>It needs a PostgreSQL account that can create databases ({@code LQM_TEST_DB_URL} names one database of the server;
 * {@code DRIFT_TEST_USERNAME}/{@code DRIFT_TEST_PASSWORD} are the account), and skips where there is none.
 */
@EnabledIfEnvironmentVariable(named = "LQM_TEST_DB_URL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "DRIFT_TEST_USERNAME", matches = ".+")
class SchemaRoundTripIntegrationTest {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.xml";

    private static String urlOf(String database) {
        String url = System.getenv("LQM_TEST_DB_URL");
        String query = url.contains("?") ? url.substring(url.indexOf('?')) : "";
        String base = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        return base.substring(0, base.lastIndexOf('/') + 1) + database + query;
    }

    private static Connection connect(String url) throws SQLException {
        return DriverManager.getConnection(url, System.getenv("DRIFT_TEST_USERNAME"), System.getenv("DRIFT_TEST_PASSWORD"));
    }

    private static void update(String url) throws Exception {
        try (Connection connection = connect(url)) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            // `seed` is the context the application itself runs with, so the seed data is part of the round trip.
            new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database).update(new Contexts("seed"), new LabelExpression());
        }
    }

    private static void rollbackEverything(String url) throws Exception {
        try (Connection connection = connect(url)) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), database)
                    .rollback(100_000, null, new Contexts("seed"), new LabelExpression());
        }
    }

    /** What the schema holds apart from Liquibase's own bookkeeping, as "kind name": tables, views, sequences, types. */
    private static List<String> objects(String url) throws SQLException {
        List<String> found = new ArrayList<>();
        String sql = """
                select 'table ' || tablename from pg_tables where schemaname = 'public' and tablename not like 'databasechangelog%'
                union all select 'view ' || viewname from pg_views where schemaname = 'public'
                union all select 'sequence ' || sequencename from pg_sequences where schemaname = 'public'
                union all select 'type ' || t.typname from pg_type t join pg_namespace n on n.oid = t.typnamespace
                   where n.nspname = 'public' and t.typtype in ('e', 'd') order by 1
                """;
        try (Connection connection = connect(url); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                found.add(rs.getString(1));
            }
        }
        return found;
    }

    @Test
    @DisplayName("update, rollback of every changeset and update again leave the same schema: nothing is missing a rollback")
    void updateRollbackUpdate() throws Exception {
        String name = "lqm_roundtrip_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String url = urlOf(name);
        try (Connection admin = connect(System.getenv("LQM_TEST_DB_URL")); Statement statement = admin.createStatement()) {
            statement.execute("create database " + name);
        }
        try {
            update(url);
            List<String> afterFirst = objects(url);
            assertThat(afterFirst).as("the schema was created").isNotEmpty();

            rollbackEverything(url);
            assertThat(objects(url)).as("rolling back every changeset removes everything it created").isEmpty();

            update(url);
            assertThat(objects(url)).as("applying again gives the same schema").isEqualTo(afterFirst);
        } finally {
            try (Connection admin = connect(System.getenv("LQM_TEST_DB_URL")); Statement statement = admin.createStatement()) {
                statement.execute("drop database if exists " + name + " with (force)");
            }
        }
    }
}
