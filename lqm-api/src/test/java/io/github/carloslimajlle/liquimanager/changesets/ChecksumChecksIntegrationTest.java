package io.github.carloslimajlle.liquimanager.changesets;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** ChecksumChanged: a changeset that ran and was edited afterwards is told apart from one that just has not run, with what changed. */
class ChecksumChecksIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";
    private static final String FILE = "db/changelog/work/A-1.xml";

    private static final String OLD = String.join("\n",
            "<databaseChangeLog>",
            "  <changeSet id=\"A-1\" author=\"p.ricardo\">",
            "    <!-- the first version -->",
            "    <addColumn tableName=\"cliente\"><column name=\"estoque\" type=\"int\"/></addColumn>",
            "  </changeSet>",
            "</databaseChangeLog>");
    private static final String NEW = String.join("\n",
            "<databaseChangeLog>",
            "  <changeSet id=\"A-1\" author=\"p.ricardo\">",
            "    <!-- renamed -->",
            "    <addColumn tableName=\"cliente\"><column name=\"quantidade_estoque\" type=\"int\"/></addColumn>",
            "  </changeSet>",
            "</databaseChangeLog>");

    private void ingest(String token, String project, String connection, String url, String changesets) {
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token,
                payloadAt("DEV", UUID.randomUUID(), project, connection, url, "update", "SUCCESS", Instant.now().minusSeconds(30), changesets));
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    private JsonNode check(String token, String project, String env, String commit, String checksum) {
        String body = "{\"project\":\"%s\",\"env\":\"%s\",\"commit\":\"%s\",\"changesets\":[{\"id\":\"A-1\",\"author\":\"p.ricardo\",\"filePath\":\"%s\",\"checksum\":\"%s\"}]}"
                .formatted(project, env, commit, FILE, checksum);
        HttpResponse<String> response = call("POST", API + "/policies/checksum-check", token, body);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    @Test
    @DisplayName("an executed changeset edited afterwards is reported with the lines that changed; the same checksum, an unknown one, or one that only ran before are not")
    void checksumChanged() {
        String ingest = serviceToken("ingest");
        String reader = serviceToken("checks:read");
        String project = unique("sum");
        String dev = unique("dev");
        String url = uniqueUrl();

        // The checksum the database holds is the one an EXECUTION reported (TestPayloads: 9:abc, commit 9f3c2ab).
        ingest(ingest, project, dev, url, changeset("A-1", "EXECUTED"));
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        jdbc.sql("update project set repo_url = 'https://gitlab.test/grp/proj.git', default_branch = 'main' where id = :id").param("id", projectId).update();
        GITLAB.files.clear();
        GITLAB.files.put(FILE + "@9f3c2ab", OLD);
        GITLAB.files.put(FILE + "@feature", NEW);

        assertThat(check(reader, project, "DEV", "feature", "9:abc")).as("same checksum").isEmpty();
        assertThat(check(reader, project, "HML", "feature", "9:zzz")).as("not executed in that environment").isEmpty();
        assertThat(check(reader, "no-such-project", "DEV", "feature", "9:zzz")).isEmpty();

        JsonNode changed = check(reader, project, "DEV", "feature", "9:zzz");
        assertThat(changed).hasSize(1);
        JsonNode entry = changed.get(0);
        assertThat(entry.get("appliedChecksum").asString()).isEqualTo("9:abc");
        assertThat(entry.get("currentChecksum").asString()).isEqualTo("9:zzz");
        assertThat(entry.get("appliedCommit").asString()).isEqualTo("9f3c2ab");
        assertThat(entry.get("sourceKnown").asBoolean()).isTrue();
        assertThat(entry.get("equivalent").asBoolean()).isFalse();
        String diff = entry.get("diff").toString();
        assertThat(diff).contains("\"op\":\"-\"").contains("estoque").contains("\"op\":\"+\"").contains("quantidade_estoque");
        assertThat(diff.indexOf("\"op\":\"-\"")).as("the removed line comes first").isLessThan(diff.indexOf("\"op\":\"+\""));
        assertThat(diff).as("comments are not part of the checksum, so they are not what changed").doesNotContain("renamed").doesNotContain("first version");

        // A run that only found it already run carries the file's checksum, not the database's: it must not overwrite what was executed.
        ingest(ingest, project, dev, url, changeset("A-1", "PREVIOUSLY_RUN").replace("9:abc", "9:other"));
        assertThat(check(reader, project, "DEV", "feature", "9:zzz").get(0).get("appliedChecksum").asString()).isEqualTo("9:abc");

        // Same text apart from the comment: the checksum differs, no line that counts does.
        GITLAB.files.put(FILE + "@same", OLD.replace("first version", "only a comment"));
        JsonNode equivalent = check(reader, project, "DEV", "same", "9:zzz");
        assertThat(equivalent.get(0).get("equivalent").asBoolean()).isTrue();

        // Without the repository the change is still told, only without the lines.
        jdbc.sql("update project set repo_url = null where id = :id").param("id", projectId).update();
        JsonNode blind = check(reader, project, "DEV", "feature", "9:zzz");
        assertThat(blind).hasSize(1);
        assertThat(blind.get(0).get("sourceKnown").asBoolean()).isFalse();
        assertThat(blind.get(0).has("diff")).isFalse();
    }

    @Test
    @DisplayName("an id applied under another author is reported when the old one is gone from the changelog, not when it is still declared")
    void identityChanged() {
        String ingest = serviceToken("ingest");
        String reader = serviceToken("checks:read");
        String project = unique("who");
        ingest(ingest, project, unique("dev"), uniqueUrl(), changeset("A-1", "EXECUTED"));

        String renamed = "{\"project\":\"%s\",\"env\":\"DEV\",\"changesets\":[{\"id\":\"A-1\",\"author\":\"sistema\",\"filePath\":\"%s\",\"checksum\":\"9:abc\"}]}"
                .formatted(project, FILE);
        JsonNode found = json(call("POST", API + "/policies/checksum-check", reader, renamed));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).get("identityChanged").asBoolean()).isTrue();
        assertThat(found.get(0).get("appliedAuthor").asString()).isEqualTo("p.ricardo");
        assertThat(found.get(0).get("author").asString()).isEqualTo("sistema");

        // The old one is still declared next to it: two changesets that share an id, nothing was renamed.
        String both = "{\"project\":\"%s\",\"env\":\"DEV\",\"changesets\":[{\"id\":\"A-1\",\"author\":\"p.ricardo\",\"filePath\":\"%s\",\"checksum\":\"9:abc\"},{\"id\":\"A-1\",\"author\":\"sistema\",\"filePath\":\"%s\",\"checksum\":\"9:abc\"}]}"
                .formatted(project, FILE, FILE);
        assertThat(json(call("POST", API + "/policies/checksum-check", reader, both))).isEmpty();

        // classpath: and a leading slash are how a file is spelled, not a different file.
        String spelled = "{\"project\":\"%s\",\"env\":\"DEV\",\"changesets\":[{\"id\":\"A-1\",\"author\":\"p.ricardo\",\"filePath\":\"classpath:%s\",\"checksum\":\"9:abc\"}]}"
                .formatted(project, FILE);
        assertThat(json(call("POST", API + "/policies/checksum-check", reader, spelled))).isEmpty();
    }

    @Test
    @DisplayName("what the environment ran and the changelog no longer declares is reported, for that changelog only")
    void removed() {
        String ingest = serviceToken("ingest");
        String reader = serviceToken("checks:read");
        String project = unique("gone");
        ingest(ingest, project, unique("dev"), uniqueUrl(), changeset("A-1", "EXECUTED"));
        String master = "db/changelog/db.changelog-master.xml";

        String without = "{\"project\":\"%s\",\"env\":\"DEV\",\"changelog\":\"%s\",\"changesets\":[{\"id\":\"A-2\",\"author\":\"p.ricardo\",\"filePath\":\"%s\",\"checksum\":\"9:new\"}]}";
        JsonNode gone = json(call("POST", API + "/policies/checksum-check", reader, without.formatted(project, master, FILE)));
        assertThat(gone).hasSize(1);
        assertThat(gone.get(0).get("removed").asBoolean()).isTrue();
        assertThat(gone.get(0).get("id").asString()).isEqualTo("A-1");

        assertThat(json(call("POST", API + "/policies/checksum-check", reader, without.formatted(project, "db/other/master.xml", FILE))))
                .as("another changelog of the project: nothing to say about it").isEmpty();
        assertThat(json(call("POST", API + "/policies/checksum-check", reader, without.formatted(project, "./" + master, FILE))))
                .as("./ and the way a path is spelled do not make it another changelog").hasSize(1);
        String declared = "{\"project\":\"%s\",\"env\":\"DEV\",\"changelog\":\"%s\",\"changesets\":[{\"id\":\"A-1\",\"author\":\"p.ricardo\",\"filePath\":\"%s\",\"checksum\":\"9:abc\"}]}"
                .formatted(project, master, FILE);
        assertThat(json(call("POST", API + "/policies/checksum-check", reader, declared))).as("still declared").isEmpty();
        String noChangelog = "{\"project\":\"%s\",\"env\":\"DEV\",\"changesets\":[{\"id\":\"A-2\",\"author\":\"p.ricardo\",\"filePath\":\"%s\",\"checksum\":\"9:new\"}]}"
                .formatted(project, FILE);
        assertThat(json(call("POST", API + "/policies/checksum-check", reader, noChangelog))).as("without the changelog named, silent").isEmpty();
    }

    @Test
    @DisplayName("the versions of a changeset show what each database ran against what the repository has now")
    void versions() {
        String ingest = serviceToken("ingest");
        String project = unique("ver");
        ingest(ingest, project, unique("dev"), uniqueUrl(), changeset("A-1", "EXECUTED"));
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        UUID changeset = jdbc.sql("select cs.id from changeset cs join changelog cl on cl.id = cs.changelog_id where cl.project_id = :p")
                .param("p", projectId).query(UUID.class).single();
        jdbc.sql("update project set repo_url = 'https://gitlab.test/grp/proj.git', default_branch = 'main' where id = :id").param("id", projectId).update();
        GITLAB.files.clear();
        GITLAB.files.put(FILE + "@9f3c2ab", OLD);
        GITLAB.files.put(FILE + "@main", NEW);

        JsonNode same = json(get(API + "/changesets/" + changeset + "/versions"));
        assertThat(same).hasSize(1);
        assertThat(same.get(0).get("changed").asBoolean()).as("what ran is what the changeset records now").isFalse();

        jdbc.sql("update changeset set checksum = '9:edited' where id = :id").param("id", changeset).update();
        JsonNode edited = json(get(API + "/changesets/" + changeset + "/versions"));
        assertThat(edited.get(0).get("changed").asBoolean()).isTrue();
        assertThat(edited.get(0).get("diff").toString()).contains("quantidade_estoque");
        assertThat(get(API + "/changesets/" + UUID.randomUUID() + "/versions").statusCode()).isEqualTo(404);
    }
}
