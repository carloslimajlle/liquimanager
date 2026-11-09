package io.github.carloslimajlle.liquimanager.drift;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import tools.jackson.databind.JsonNode;

/**
 * Drift and the scanner against a REAL SQL Server, the target the product exists for: the Postgres tests prove the logic,
 * this one proves the dialect (identifiers, DATABASECHANGELOG columns, snapshots of SQL Server objects).
 *
 * <p>It runs where {@code MSSQL_TEST_URL} is set (a JDBC URL naming a database) and {@code MSSQL_TEST_USERNAME} /
 * {@code MSSQL_TEST_PASSWORD} hold an account that can create schemas and tables there. The same account is what the
 * connection's secret resolves to (provider ENV, reference {@code MSSQL_TEST}). The local one is
 * {@code docker compose --profile target up}; see deploy/mssql/README.md.
 *
 * <p>Each test works in a schema of its own and removes it afterwards. One test also needs an account that can read
 * the database but does NOT hold VIEW DEFINITION ({@code MSSQL_BLIND_USERNAME} / {@code MSSQL_BLIND_PASSWORD}); it
 * is skipped without them.
 */
@EnabledIfEnvironmentVariable(named = "MSSQL_TEST_URL", matches = ".+")
class SqlServerTargetIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private String workspace;
    private String connectionId;
    private String schema;
    private Path dir;
    private int applied;
    private final List<String> tables = new ArrayList<>();

    private static Connection target() throws SQLException {
        return DriverManager.getConnection(System.getenv("MSSQL_TEST_URL"),
                System.getenv("MSSQL_TEST_USERNAME"), System.getenv("MSSQL_TEST_PASSWORD"));
    }

    private void exec(String sql) throws SQLException {
        try (Connection connection = target(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private HttpResponse<String> in(String method, String path, String body) {
        return callIn(workspace, method, API + path, body);
    }

    @BeforeEach
    void scenario() throws Exception {
        workspace = unique("ws");
        assertThat(post(API + "/workspaces", "{\"slug\":\"%s\",\"name\":\"SQL Server\"}".formatted(workspace)).statusCode()).isEqualTo(201);
        String env = json(in("POST", "/environments", "{\"code\":\"HML\",\"name\":\"HML\",\"sortOrder\":1,\"isProduction\":false}"))
                .get("id").asString();
        schema = "lqm_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        exec("CREATE SCHEMA " + schema);
        dir = Files.createTempDirectory("lqm-mssql");

        HttpResponse<String> response = in("POST", "/connections", """
                { "environmentId": "%s", "slug": "%s", "displayName": "sqlserver", "engine": "SQLSERVER", "jdbcUrl": "%s",
                  "defaultSchema": "%s", "secretProvider": "ENV", "secretRef": "MSSQL_TEST", "driftEnabled": true }
                """.formatted(env, unique("mssql"), System.getenv("MSSQL_TEST_URL"), schema));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        connectionId = json(response).get("id").asString();
    }

    @AfterEach
    void cleanUp() throws Exception {
        for (String table : tables) {
            exec("DROP TABLE IF EXISTS " + schema + "." + table);
        }
        for (String table : List.of("DATABASECHANGELOG", "DATABASECHANGELOGLOCK")) {
            exec("DROP TABLE IF EXISTS " + schema + "." + table);
        }
        exec("DROP SCHEMA IF EXISTS " + schema);
    }

    /** Runs Liquibase on the schema with {@code total} changesets: the new ones are the deployment. */
    private void liquibase(int total) throws Exception {
        StringBuilder xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd">
                """);
        for (int i = 1; i <= total; i++) {
            xml.append("""
                    <changeSet id="SUP-%d" author="p.ricardo">
                      <createTable tableName="t%d" schemaName="%s"><column name="id" type="int"><constraints primaryKey="true"/></column>
                        <column name="nome" type="NVARCHAR(40)"/></createTable>
                    </changeSet>
                    """.formatted(i, i, schema));
            if (!tables.contains("t" + i)) {
                tables.add("t" + i);
            }
        }
        xml.append("</databaseChangeLog>");
        Files.writeString(dir.resolve("changelog.xml"), xml.toString());
        try (Connection connection = target()) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            // No setDefaultSchemaName: SQL Server refuses it when the schema is not the login's own, which is exactly the case
            // under test (the account's default schema is dbo, the monitored one is not). The objects name their schema.
            database.setLiquibaseSchemaName(schema);
            new Liquibase("changelog.xml", new DirectoryResourceAccessor(dir), database)
                    .update(new Contexts(), new LabelExpression());
        }
        // Every run in this JVM shares Liquibase's DEPLOYMENT_ID; in production each deployment is its own process.
        String fresh = String.valueOf(1_000_000_000L + Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
        for (int i = applied + 1; i <= total; i++) {
            exec("UPDATE " + schema + ".DATABASECHANGELOG SET DEPLOYMENT_ID = '" + fresh + "' WHERE ID = 'SUP-" + i + "'");
        }
        applied = total;
        // DATEEXECUTED has the clock's resolution; keep deployments apart so "newer than last seen" is unambiguous.
        Thread.sleep(1100);
    }

    private JsonNode scan() {
        HttpResponse<String> response = in("POST", "/connections/" + connectionId + "/scan", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private JsonNode driftRun() {
        HttpResponse<String> response = in("POST", "/connections/" + connectionId + "/drift-runs", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode run = json(response);
        return json(in("GET", "/drift/runs/" + run.get("id").asString(), null));
    }

    private static boolean has(JsonNode run, String change, String type, String name) {
        for (JsonNode item : run.get("items")) {
            if (change.equals(item.get("change").asString()) && type.equals(item.get("objectType").asString())
                    && name.equalsIgnoreCase(item.get("objectName").asString())) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("the connection test reaches SQL Server and reports its version")
    void connectionTest() {
        JsonNode result = json(in("POST", "/connections/" + connectionId + "/test", null));
        assertThat(result.get("ok").asBoolean()).as(result.toString()).isTrue();
        assertThat(result.get("dbVersion").asString()).contains("SQL Server");
    }

    @Test
    @DisplayName("the scanner imports the history of a SQL Server database, then sees only what ran outside the process")
    void scanner() throws Exception {
        liquibase(1);
        JsonNode first = scan();
        assertThat(first.get("ok").asBoolean()).as(first.toString()).isTrue();
        assertThat(first.get("firstPass").asBoolean()).isTrue();
        assertThat(first.get("outOfProcess").asInt()).isZero();

        assertThat(scan().get("outOfProcess").asInt()).as("nothing new").isZero();

        liquibase(2);
        JsonNode after = scan();
        assertThat(after.get("ok").asBoolean()).as(after.toString()).isTrue();
        assertThat(after.get("outOfProcess").asInt()).as("a deployment nobody reported").isEqualTo(1);
    }

    @Test
    @DisplayName("drift on SQL Server is silent on a clean database and names what someone changed by hand")
    void drift() throws Exception {
        liquibase(1);
        assertThat(in("POST", "/connections/" + connectionId + "/drift-reference", null).statusCode()).isEqualTo(200);

        JsonNode clean = driftRun();
        assertThat(clean.get("hasDrift").asBoolean()).as("a clean database has no drift: %s", clean).isFalse();

        exec("ALTER TABLE " + schema + ".t1 ADD extra INT NULL");
        exec("ALTER TABLE " + schema + ".t1 ALTER COLUMN nome NVARCHAR(80) NULL");
        exec("CREATE INDEX ix_hotfix ON " + schema + ".t1 (nome)");
        exec("CREATE TABLE " + schema + ".hotfix (id INT NOT NULL PRIMARY KEY)");
        tables.add("hotfix");

        JsonNode drifted = driftRun();
        assertThat(drifted.get("hasDrift").asBoolean()).as(drifted.toString()).isTrue();
        assertThat(has(drifted, "ADDED", "COLUMN", "t1.extra")).as(drifted.toString()).isTrue();
        assertThat(has(drifted, "MODIFIED", "COLUMN", "t1.nome")).as(drifted.toString()).isTrue();
        assertThat(has(drifted, "ADDED", "INDEX", "ix_hotfix")).as(drifted.toString()).isTrue();
        assertThat(has(drifted, "ADDED", "TABLE", "hotfix")).as(drifted.toString()).isTrue();
    }

    @Test
    @DisplayName("an account without VIEW DEFINITION is refused by drift, because SQL Server would hide definitions and drift would invent changes")
    @EnabledIfEnvironmentVariable(named = "MSSQL_BLIND_USERNAME", matches = ".+")
    void accountWithoutViewDefinition() throws Exception {
        liquibase(1);
        exec("GRANT SELECT ON SCHEMA::" + schema + " TO " + System.getenv("MSSQL_BLIND_USERNAME"));
        String env = json(in("GET", "/environments", null)).get(0).get("id").asString();
        HttpResponse<String> created = in("POST", "/connections", """
                { "environmentId": "%s", "slug": "%s", "displayName": "blind", "engine": "SQLSERVER", "jdbcUrl": "%s",
                  "defaultSchema": "%s", "secretProvider": "ENV", "secretRef": "MSSQL_BLIND", "driftEnabled": true }
                """.formatted(env, unique("blind"), System.getenv("MSSQL_TEST_URL"), schema));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String blind = json(created).get("id").asString();

        JsonNode test = json(in("POST", "/connections/" + blind + "/test", null));
        assertThat(test.get("ok").asBoolean()).as(test.toString()).isTrue();
        assertThat(test.get("warnings").get(0).asString()).contains("VIEW DEFINITION");

        HttpResponse<String> run = in("POST", "/connections/" + blind + "/drift-runs", null);
        JsonNode result = json(run);
        assertThat(result.get("status").asString()).as(run.body()).isEqualTo("FAILED");
        assertThat(result.get("error").asString()).contains("VIEW DEFINITION");

        // The account that can see definitions is not warned.
        JsonNode fine = json(in("POST", "/connections/" + connectionId + "/test", null));
        assertThat(fine.has("warnings")).isFalse();
    }
}
