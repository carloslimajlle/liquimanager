package io.github.carloslimajlle.liquimanager.drift;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.catalog.SecretResolver;
import io.github.carloslimajlle.liquimanager.common.LqmException;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;

/**
 * Opens a monitored database, the way drift and the scanner need to: with credentials resolved from the
 * connection's secret reference at this very moment, held in memory, and gone when the handle is closed.
 *
 * <p>Read access is all anyone needs from here. Nothing in drift or the scanner issues anything but a
 * query, and the account behind the secret should be able to do no more (docs/09).
 */
@Component
public class TargetConnections {

    /** A live connection to a monitored database. Close it. */
    public static final class Target implements AutoCloseable {
        private final Connection jdbc;
        private final Database database;
        private final String schema;
        private final String slug;

        Target(Connection jdbc, Database database, String schema, String slug) {
            this.jdbc = jdbc;
            this.database = database;
            this.schema = schema;
            this.slug = slug;
        }

        public Connection jdbc() {
            return jdbc;
        }

        public Database database() {
            return database;
        }

        /** The schema the connection is about, or null for the database default. A list ("dbo, audit") when it is about several. */
        public String schema() {
            return schema;
        }

        /** The schema that holds Liquibase's own tables (DATABASECHANGELOG): the first one given, or null for the database default. */
        public String primarySchema() {
            return schemas().isEmpty() ? null : schemas().get(0);
        }

        /** The schemas the connection is about, in the order given; empty for the database default. */
        public java.util.List<String> schemas() {
            if (schema == null) {
                return java.util.List.of();
            }
            return java.util.Arrays.stream(schema.split(",")).map(String::trim).filter(name -> !name.isEmpty()).toList();
        }

        public String slug() {
            return slug;
        }

        @Override
        public void close() {
            try {
                database.close();
            } catch (Exception e) {
                try {
                    jdbc.close();
                } catch (SQLException ignored) {
                    // a connection that cannot be closed has nothing more to tell us
                }
            }
        }
    }

    private final JdbcClient jdbc;
    private final SecretResolver secrets;

    TargetConnections(JdbcClient jdbc, SecretResolver secrets) {
        this.jdbc = jdbc;
        this.secrets = secrets;
    }

    public Target open(UUID workspace, UUID connectionId) {
        var row = jdbc.sql("""
                select slug, jdbc_url, default_schema, secret_provider, secret_ref
                  from db_connection where id = :id and workspace_id = :ws and archived_at is null
                """).param("id", connectionId).param("ws", workspace).query().listOfRows().stream().findFirst()
                .orElseThrow(() -> LqmException.notFound("connection"));
        SecretResolver.Credentials credentials = secrets.resolve((String) row.get("secret_provider"), (String) row.get("secret_ref"));

        Properties properties = new Properties();
        if (credentials.username() != null) {
            properties.setProperty("user", credentials.username());
        }
        if (credentials.password() != null) {
            properties.setProperty("password", credentials.password());
        }
        Connection connection;
        try {
            DriverManager.setLoginTimeout(10);
            connection = DriverManager.getConnection((String) row.get("jdbc_url"), properties);
        } catch (SQLException e) {
            // The driver's message can carry the URL and the user; only say that it failed, and why in general terms.
            throw new LqmException(HttpStatus.BAD_GATEWAY, "LQM-050",
                    "could not connect to " + row.get("slug") + " (SQLState " + e.getSQLState() + ")");
        }
        try {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            String schema = (String) row.get("default_schema");
            if (schema != null && !schema.isBlank()) {
                try {
                    // The first of several is the default; every one is named explicitly where it matters.
                    database.setDefaultSchemaName(schema.split(",")[0].trim());
                } catch (RuntimeException refused) {
                    // SQL Server cannot set a default schema per session: it only accepts the login's own. The
                    // connection's schema travels in Target.schema() and every query names it, so this is not needed.
                }
            }
            return new Target(connection, database, schema == null || schema.isBlank() ? null : schema, (String) row.get("slug"));
        } catch (LiquibaseException e) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // see above
            }
            throw new LqmException(HttpStatus.BAD_GATEWAY, "LQM-050", "unsupported database for " + row.get("slug"));
        }
    }
}
