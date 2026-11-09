package io.github.carloslimajlle.liquimanager.changesets;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import tools.jackson.databind.JsonNode;

/** A project seen from governance: its findings, the drift of its databases and everything waiting for approval in it. */
class ProjectGovernanceIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    private void ingest(String token, String project, String env, String connection, String url, String changesets) {
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token,
                payloadAt(env, UUID.randomUUID(), project, connection, url, "update", "SUCCESS", Instant.now().minusSeconds(30), changesets));
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
    }

    private String environmentId(String code) {
        for (JsonNode env : json(get(API + "/environments"))) {
            if (code.equals(env.get("code").asString())) {
                return env.get("id").asString();
            }
        }
        throw new AssertionError("seeded environment missing: " + code);
    }

    @Test
    @DisplayName("findings are counted by result and listed with their changeset; drift lists every database, the unchecked one with no run")
    void findingsAndDrift() {
        String token = serviceToken("ingest");
        String project = unique("gov");
        String dev = unique("dev");
        String hml = unique("hml");
        ingest(token, project, "DEV", dev, uniqueUrl(), changeset("G-1", "EXECUTED"));
        ingest(token, project, "HML", hml, uniqueUrl(), changeset("G-1", "EXECUTED"));
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();

        var op = jdbc.sql("select o.id, o.started_at from operation o where o.project_id = :p order by o.started_at limit 1")
                .param("p", projectId).query().singleRow();
        UUID check = jdbc.sql("select id from policy_check limit 1").query(UUID.class).single();
        UUID changeset = jdbc.sql("select cs.id from changeset cs join changelog cl on cl.id = cs.changelog_id where cl.project_id = :p")
                .param("p", projectId).query(UUID.class).list().get(0);
        for (String result : new String[] {"BLOCKED", "WARNED", "WARNED"}) {
            jdbc.sql("""
                    insert into policy_finding (id, operation_id, operation_started_at, check_id, changeset_id, severity, result, message, created_at)
                    values (:id, :op, :at, :check, :cs, 3, :result, 'drop table', now())
                    """).param("id", UUID.randomUUID()).param("op", op.get("id")).param("at", op.get("started_at")).param("check", check)
                    .param("cs", changeset).param("result", result).update();
        }
        UUID devConnection = jdbc.sql("select id from db_connection where slug = :s").param("s", dev).query(UUID.class).single();
        jdbc.sql("""
                insert into drift_run (id, db_connection_id, reference_type, status, has_drift, added, removed, modified, started_at)
                values (:id, :c, 'SNAPSHOT', 'SUCCESS', true, 2, 0, 1, :at)
                """).param("id", UUID.randomUUID()).param("c", devConnection).param("at", Timestamp.from(Instant.now())).update();

        JsonNode findings = json(get(API + "/projects/" + projectId + "/findings"));
        assertThat(findings.get("totalElements").asInt()).isEqualTo(3);
        assertThat(findings.get("counts").get("BLOCKED").asInt()).isEqualTo(1);
        assertThat(findings.get("counts").get("WARNED").asInt()).isEqualTo(2);
        assertThat(findings.get("content").get(0).get("changeset").asString()).isEqualTo("G-1");
        assertThat(findings.get("content").get(0).get("changesetId").asString()).isNotBlank();
        assertThat(json(get(API + "/projects/" + projectId + "/findings?result=blocked")).get("totalElements").asInt()).isEqualTo(1);

        JsonNode drift = json(get(API + "/projects/" + projectId + "/drift"));
        assertThat(drift).hasSize(2);
        JsonNode checked = null;
        JsonNode never = null;
        for (JsonNode entry : drift) {
            if (dev.equals(entry.get("connection").asString())) {
                checked = entry;
            } else {
                never = entry;
            }
        }
        assertThat(checked.get("hasDrift").asBoolean()).isTrue();
        assertThat(checked.get("added").asInt()).isEqualTo(2);
        assertThat(never.has("runId")).as("never checked is not clean").isFalse();

        assertThat(get(API + "/projects/" + UUID.randomUUID() + "/findings").statusCode()).isEqualTo(404);
        assertThat(get(API + "/projects/" + UUID.randomUUID() + "/drift").statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("the approvals of a project list every open request, each marked with whether the caller may decide it")
    void approvals() {
        String token = serviceToken("ingest");
        String project = unique("appr");
        String dev = unique("dev");
        String hml = unique("hml");
        ingest(token, project, "DEV", dev, uniqueUrl(), changeset("A-1", "EXECUTED"));
        ingest(token, project, "HML", hml, uniqueUrl(), changeset("A-1", "EXECUTED"));
        UUID projectId = jdbc.sql("select id from project where slug = :s").param("s", project).query(UUID.class).single();
        String pipeline = json(post(API + "/pipelines", "{\"name\":\"%s\",\"projectId\":\"%s\"}".formatted(unique("PL"), projectId))).get("id").asString();
        String hmlStage = null;
        int order = 1;
        for (String[] target : new String[][] {{"DEV", dev}, {"HML", hml}}) {
            UUID connection = jdbc.sql("select id from db_connection where slug = :s").param("s", target[1]).query(UUID.class).single();
            hmlStage = json(post(API + "/pipelines/" + pipeline + "/stages", "{\"sortOrder\":%d,\"environmentId\":\"%s\",\"dbConnectionId\":\"%s\",\"requiresApproval\":true}"
                    .formatted(order++, environmentId(target[0]), connection))).get("id").asString();
        }
        jdbc.sql("""
                insert into promotion (id, workspace_id, pipeline_id, stage_id, environment, status, requested_by)
                select :id, workspace_id, :pl, :st, 'HML', 'REQUESTED', 'system' from project where id = :project
                """).param("id", UUID.randomUUID()).param("pl", UUID.fromString(pipeline)).param("st", UUID.fromString(hmlStage))
                .param("project", projectId).update();

        // The unnamed development caller holds everything, so it may decide a request that is not its own.
        JsonNode approvals = json(get(API + "/projects/" + projectId + "/approvals"));
        assertThat(approvals.get("total").asInt()).isEqualTo(1);
        JsonNode item = approvals.get("items").get(0);
        assertThat(item.get("kind").asString()).isEqualTo("PROMOTION");
        assertThat(item.get("requestedBy").asString()).isEqualTo("system");
        assertThat(item.has("decidable")).isTrue();
        assertThat(item.get("mine").asBoolean()).isFalse();

        String other = unique("other");
        String otherProject = unique("none");
        ingest(token, otherProject, "DEV", unique("dev"), uniqueUrl(), changeset("N-1", "EXECUTED"));
        UUID otherId = jdbc.sql("select id from project where slug = :s").param("s", otherProject).query(UUID.class).single();
        assertThat(json(get(API + "/projects/" + otherId + "/approvals")).get("total").asInt()).as("only this project's requests").isZero();
        assertThat(other).isNotBlank();
        assertThat(get(API + "/projects/" + UUID.randomUUID() + "/approvals").statusCode()).isEqualTo(404);
    }
}
