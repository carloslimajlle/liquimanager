package io.github.carloslimajlle.liquimanager.changesets;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import io.github.carloslimajlle.liquimanager.developer.RepositoryFiles;
import tools.jackson.databind.JsonNode;

/** Choosing what to generate: the changesets, a release, a tag, or what has not reached an environment yet. The SQL itself is in {@link ChangesetSqlGeneratorTest}. */
class ChangesetSqlIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private static final String XML = String.join("\n",
            "<databaseChangeLog xmlns=\"http://www.liquibase.org/xml/ns/dbchangelog\"",
            "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"",
            "    xsi:schemaLocation=\"http://www.liquibase.org/xml/ns/dbchangelog http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-latest.xsd\">",
            "  <changeSet id=\"G-1\" author=\"p.ricardo\">",
            "    <createTable tableName=\"cliente\"><column name=\"id\" type=\"int\"/></createTable>",
            "  </changeSet>",
            "</databaseChangeLog>");

    @MockitoBean
    private RepositoryFiles files;

    private void ingest(String token, String project, String env, String connection, String url, String changesets) {
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token,
                payloadAt(env, UUID.randomUUID(), project, connection, url, "update", "SUCCESS", Instant.now().minusSeconds(30), changesets));
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    private static List<String> ids(JsonNode answer) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : answer.get("changesets")) {
            out.add(item.get("changesetId").asString());
        }
        return out;
    }

    @Test
    @DisplayName("selects by changesets, release, tag and 'not yet in the next environment'; the dialect follows the engine")
    void selections() {
        String token = serviceToken("ingest");
        String project = unique("sql");
        ingest(token, project, "DEV", unique("dev"), uniqueUrl(), String.join(",", changeset("S-1", "EXECUTED"), changeset("S-2", "EXECUTED")));
        ingest(token, project, "TST", unique("tst"), uniqueUrl(), changeset("S-1", "EXECUTED"));
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        String base = API + "/projects/" + projectId + "/changesets/sql";

        JsonNode release = json(get(base + "?release=release-2.1.0"));
        assertThat(release.get("mode").asString()).isEqualTo("RELEASE");
        assertThat(ids(release)).containsExactlyInAnyOrder("S-1", "S-2");
        assertThat(release.get("dialect").asString()).as("the payload connections are SQL Server").isEqualTo("mssql");
        assertThat(release.get("sql").asString()).contains("Selection: RELEASE release-2.1.0");
        assertThat(release.get("generated").asInt()).as("no GitLab here, so nothing is generated, and each says why").isZero();
        assertThat(release.get("changesets").get(0).get("warning").asString()).isIn("NO_REPOSITORY", "NOT_CONFIGURED");

        assertThat(ids(json(get(base + "?release=release-9.9.9")))).isEmpty();
        assertThat(ids(json(get(base + "?notIn=TST")))).as("S-2 ran in DEV and has not reached TST").containsExactly("S-2");
        assertThat(ids(json(get(base + "?notIn=hml")))).as("neither ran in HML; both ran earlier").containsExactlyInAnyOrder("S-1", "S-2");

        jdbc.sql("""
                update changeset_state set tag = 'v1.0'
                 where changeset_id in (select cs.id from changeset cs join changelog cl on cl.id = cs.changelog_id
                                         where cl.project_id = :p and cs.cs_id = 'S-2')
                """).param("p", projectId).update();
        assertThat(ids(json(get(base + "?tag=v1.0")))).containsExactly("S-2");

        UUID one = jdbc.sql("""
                select cs.id from changeset cs join changelog cl on cl.id = cs.changelog_id
                 where cl.project_id = :p and cs.cs_id = 'S-1'
                """).param("p", projectId).query(UUID.class).single();
        JsonNode chosen = json(get(base + "?ids=" + one + "&dialect=postgresql"));
        assertThat(ids(chosen)).containsExactly("S-1");
        assertThat(chosen.get("dialect").asString()).isEqualTo("postgresql");
    }

    @Test
    @DisplayName("refuses no selector, two selectors, an unknown dialect, an unknown environment and a path-like ref")
    void refusals() {
        String token = serviceToken("ingest");
        String project = unique("sqlbad");
        ingest(token, project, "DEV", unique("dev"), uniqueUrl(), changeset("B-1", "EXECUTED"));
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        String base = API + "/projects/" + projectId + "/changesets/sql";

        assertThat(get(base).statusCode()).isEqualTo(422);
        assertThat(get(base + "?release=release-2.1.0&tag=v1").statusCode()).isEqualTo(422);
        assertThat(get(base + "?release=release-2.1.0&dialect=db2").statusCode()).isEqualTo(422);
        assertThat(get(base + "?release=release-2.1.0&ref=../x").statusCode()).isEqualTo(422);
        assertThat(get(base + "?release=a%20b").statusCode()).isEqualTo(422);
        assertThat(get(base + "?notIn=NOPE").statusCode()).isEqualTo(404);
        assertThat(get(API + "/projects/" + UUID.randomUUID() + "/changesets/sql?release=r").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("reads the file from the repository and generates the SQL of the changeset; a file that is not there leaves a warning")
    void generates() {
        String token = serviceToken("ingest");
        String project = unique("sqlgen");
        ingest(token, project, "DEV", unique("dev"), uniqueUrl(), String.join(",", changeset("G-1", "EXECUTED"), changeset("G-2", "EXECUTED")));
        jdbc.sql("update project set repo_url = 'https://gitlab.example.com/group/app.git', default_branch = 'main' where slug = :s")
                .param("s", project).update();
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        Mockito.when(files.configured()).thenReturn(true);
        Mockito.when(files.fetch("group/app", "db/changelog/work/G-1.xml", "main")).thenReturn(XML);

        JsonNode answer = json(get(API + "/projects/" + projectId + "/changesets/sql?release=release-2.1.0"));

        assertThat(answer.get("count").asInt()).isEqualTo(2);
        assertThat(answer.get("generated").asInt()).as("G-1 is in the file; G-2's file is not in the repository").isEqualTo(1);
        assertThat(answer.get("sql").asString()).contains("-- Changeset db/changelog/work/G-1.xml::G-1::p.ricardo (main)").containsIgnoringCase("create table");
        for (JsonNode item : answer.get("changesets")) {
            if (item.get("changesetId").asString().equals("G-1")) {
                assertThat(item.get("statements").asInt()).isPositive();
                assertThat(item.has("warning") && !item.get("warning").isNull()).as("G-1 has no warning").isFalse();
            } else {
                assertThat(item.get("warning").asString()).isEqualTo("NOT_FOUND");
            }
        }
    }
}
