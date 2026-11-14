package io.github.carloslimajlle.liquimanager.changesets;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import io.github.carloslimajlle.liquimanager.developer.RepositoryFiles;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.parser.ChangeLogParserFactory;
import liquibase.resource.DirectoryResourceAccessor;
import tools.jackson.databind.JsonNode;

/**
 * The CDC check against a REAL SQL Server with Change Data Capture on, and the recapture script run on it. Needs a database where
 * {@code sys.sp_cdc_enable_db} has been run and an account that owns it:
 * {@code CDC_TEST_URL} (a JDBC URL with {@code databaseName}), {@code CDC_TEST_USERNAME} and {@code CDC_TEST_PASSWORD}. Without them
 * the class is skipped, like the other tests that need a server of their own.
 */
@EnabledIfEnvironmentVariable(named = "CDC_TEST_URL", matches = ".+")
class ChangesetCdcIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    @MockitoBean
    private RepositoryFiles files;

    private static Connection admin() throws Exception {
        return DriverManager.getConnection(System.getenv("CDC_TEST_URL"), System.getenv("CDC_TEST_USERNAME"), System.getenv("CDC_TEST_PASSWORD"));
    }

    private static void run(String sql) throws Exception {
        try (Connection connection = admin(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<String> instances(String table) throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection connection = admin(); Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("select capture_instance from cdc.change_tables where source_object_id = object_id('dbo." + table
                     + "') order by create_date, capture_instance")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private static String changesetXml(String id, String body) {
        return String.join("\n",
                "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"",
                "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
                "    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd\">",
                "  <changeSet id=\"" + id + "\" author=\"p.ricardo\">" + body + "</changeSet>",
                "</databaseChangeLog>");
    }

    @Test
    @DisplayName("finds the changesets that touch a tracked table, ignores the rest, and the recapture it writes runs on SQL Server and swaps the instance")
    void recapture(@TempDir Path dir) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String tracked = "lqm_cdc_" + suffix;
        String plain = "lqm_plain_" + suffix;
        run("create table dbo." + tracked + " (id int identity primary key, nome varchar(50) not null, tel varchar(20) null)");
        run("create table dbo." + plain + " (id int identity primary key, nome varchar(50) not null)");
        run("exec sys.sp_cdc_enable_table @source_schema = N'dbo', @source_name = N'" + tracked + "', @role_name = NULL, @supports_net_changes = 1");
        try {
            String token = serviceToken("ingest");
            String project = unique("cdc");
            String ids = String.join(",", changeset("K-1" + suffix, "EXECUTED"), changeset("K-2" + suffix, "EXECUTED"), changeset("K-3" + suffix, "EXECUTED"));
            HttpResponse<String> ingested = call("POST", API + "/ingest/operations", token,
                    payloadAt("DEV", UUID.randomUUID(), project, unique("c"), uniqueUrl(), "update", "SUCCESS", Instant.now().minusSeconds(30), ids));
            assertThat(ingested.statusCode()).as(ingested.body()).isIn(200, 202);
            jdbc.sql("update project set repo_url = 'https://gitlab.example.com/group/app.git', default_branch = 'main' where slug = :s")
                    .param("s", project).update();
            UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();

            Mockito.when(files.configured()).thenReturn(true);
            Mockito.when(files.fetch("group/app", "db/changelog/work/K-1" + suffix + ".xml", "main")).thenReturn(changesetXml("K-1" + suffix,
                    "<modifyDataType tableName=\"" + tracked + "\" columnName=\"nome\" newDataType=\"varchar(300)\"/>"));
            Mockito.when(files.fetch("group/app", "db/changelog/work/K-2" + suffix + ".xml", "main")).thenReturn(changesetXml("K-2" + suffix,
                    "<addColumn tableName=\"" + tracked + "\"><column name=\"email\" type=\"varchar(100)\"/></addColumn>"));
            Mockito.when(files.fetch("group/app", "db/changelog/work/K-3" + suffix + ".xml", "main")).thenReturn(changesetXml("K-3" + suffix,
                    "<modifyDataType tableName=\"" + plain + "\" columnName=\"nome\" newDataType=\"varchar(300)\"/>"));

            String environment = json(get(API + "/environments")).get(0).get("id").asString();
            HttpResponse<String> created = post(API + "/connections", """
                    { "environmentId": "%s", "slug": "%s", "displayName": "cdc", "engine": "sqlserver", "jdbcUrl": "%s",
                      "secretProvider": "ENV", "secretRef": "cdc_test" }
                    """.formatted(environment, unique("cdctgt"), System.getenv("CDC_TEST_URL")));
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            String connection = json(created).get("id").asString();

            JsonNode answer = json(get(API + "/projects/" + projectId + "/changesets/cdc?release=release-2.1.0&connection=" + connection));

            assertThat(answer.get("cdcEnabled").asBoolean()).isTrue();
            assertThat(answer.get("count").asInt()).isEqualTo(3);
            assertThat(answer.get("breaking").asInt()).as("only the retyped column of the tracked table breaks anything").isEqualTo(1);
            for (JsonNode item : answer.get("changesets")) {
                String id = item.get("changesetId").asString();
                if (id.startsWith("K-1")) {
                    assertThat(item.get("impacts")).hasSize(1);
                    assertThat(item.get("impacts").get(0).get("kind").asString()).isEqualTo("COLUMN_ALTERED");
                    assertThat(item.get("impacts").get(0).get("table").asString()).isEqualTo("dbo." + tracked);
                    assertThat(item.get("impacts").get(0).get("breaking").asBoolean()).isTrue();
                } else if (id.startsWith("K-2")) {
                    assertThat(item.get("impacts").get(0).get("kind").asString()).isEqualTo("COLUMN_ADDED");
                    assertThat(item.get("impacts").get(0).get("breaking").asBoolean()).isFalse();
                } else {
                    assertThat(item.get("impacts")).as("a table CDC does not track").isEmpty();
                }
            }
            assertThat(answer.get("tables")).hasSize(1);
            JsonNode recapture = answer.get("recapture");
            assertThat(recapture.get("tables")).extracting(JsonNode::asString).containsExactly("dbo." + tracked);

            // The scripts are not decoration: they run, in order, and swap the instance.
            assertThat(instances(tracked)).containsExactly("dbo_" + tracked);
            run(recapture.get("createSql").asString());
            assertThat(instances(tracked)).as("step 1 adds the new instance and leaves the current one").containsExactly("dbo_" + tracked, "dbo_" + tracked + "_v2");
            run(recapture.get("createSql").asString());
            assertThat(instances(tracked)).as("running step 1 again changes nothing and does not fail").hasSize(2);
            run(recapture.get("removeSql").asString());
            assertThat(instances(tracked)).as("step 2 removes the old one").containsExactly("dbo_" + tracked + "_v2");

            // The changesets it writes parse, and carry the rollback the product asks of every changeset.
            Files.writeString(dir.resolve("recapture.xml"), "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"\n"
                    + "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                    + "    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd\">\n"
                    + recapture.get("createChangesets").asString() + recapture.get("removeChangesets").asString() + "</databaseChangeLog>", StandardCharsets.UTF_8);
            DirectoryResourceAccessor accessor = new DirectoryResourceAccessor(dir);
            Database database = DatabaseFactory.getInstance().openDatabase("offline:mssql", null, null, null, null, null, null, accessor);
            List<ChangeSet> changeSets = ChangeLogParserFactory.getInstance().getParser("recapture.xml", accessor)
                    .parse("recapture.xml", new ChangeLogParameters(database), accessor).getChangeSets();
            assertThat(changeSets).hasSize(2);
            assertThat(changeSets).allMatch(ChangeSet::hasCustomRollbackChanges);
        } finally {
            for (String table : List.of(tracked, plain)) {
                try {
                    for (String instance : instances(table)) {
                        run("exec sys.sp_cdc_disable_table @source_schema = N'dbo', @source_name = N'" + table + "', @capture_instance = N'" + instance + "'");
                    }
                    run("drop table dbo." + table);
                } catch (Exception ignored) {
                    // cleanup of a scratch table
                }
            }
        }
    }

    @Test
    @DisplayName("refuses a connection that is not SQL Server, and one that does not exist")
    void refusals() {
        String token = serviceToken("ingest");
        String project = unique("cdcbad");
        HttpResponse<String> ingested = call("POST", API + "/ingest/operations", token,
                payloadAt("DEV", UUID.randomUUID(), project, unique("c"), uniqueUrl(), "update", "SUCCESS", Instant.now().minusSeconds(30), changeset("B-1", "EXECUTED")));
        assertThat(ingested.statusCode()).isIn(200, 202);
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        String base = API + "/projects/" + projectId + "/changesets/cdc?release=release-2.1.0&connection=";

        assertThat(get(base + UUID.randomUUID()).statusCode()).isEqualTo(404);
        UUID postgres = jdbc.sql("select id from db_connection where engine = 'POSTGRESQL' limit 1").query(UUID.class).optional().orElse(null);
        if (postgres != null) {
            assertThat(get(base + postgres).statusCode()).isEqualTo(422);
        }
        assertThat(get(API + "/projects/" + projectId + "/changesets/cdc?release=release-2.1.0").statusCode()).as("the connection is required").isIn(400, 422);
    }
}
