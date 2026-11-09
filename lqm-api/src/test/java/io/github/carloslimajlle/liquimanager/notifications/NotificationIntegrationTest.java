package io.github.carloslimajlle.liquimanager.notifications;

import static io.github.carloslimajlle.liquimanager.TestPayloads.changeset;
import static io.github.carloslimajlle.liquimanager.TestPayloads.payloadAt;
import static io.github.carloslimajlle.liquimanager.TestPayloads.uniqueUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.carloslimajlle.liquimanager.AbstractDatabaseTest;
import io.github.carloslimajlle.liquimanager.ingest.OperationIngested;
import tools.jackson.databind.JsonNode;

/** From an event to a message in the e-mail service, through the rules, the queue and the retries. */
class NotificationIntegrationTest extends AbstractDatabaseTest {

    private static final String API = "/api/v1";

    @Autowired
    NotificationJobs jobs;

    @Autowired
    NotificationListener listener;

    @Autowired
    NotificationService notifications;

    private String workspace;
    private UUID workspaceId;
    private String project;
    private String token;
    /** Unique to the test: the e-mail service and the delivery queue are shared with every other test. */
    private String addressee;

    @BeforeEach
    void scenario() {
        EMAIL.status = 200;
        addressee = "dba-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        workspace = unique("ws");
        workspaceId = UUID.fromString(json(post(API + "/workspaces",
                "{\"slug\":\"%s\",\"name\":\"Notify\"}".formatted(workspace))).get("id").asString());
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"DEV\",\"name\":\"DEV\",\"sortOrder\":1,\"isProduction\":false}");
        callIn(workspace, "POST", API + "/environments", "{\"code\":\"PRD\",\"name\":\"PRD\",\"sortOrder\":2,\"isProduction\":true}");
        project = unique("proj");
        token = tokens.issue(workspaceId, unique("t"), List.of("ingest"), null, Instant.now().plusSeconds(3600), "it").token();
    }

    private HttpResponse<String> in(String method, String path, String body) {
        return callIn(workspace, method, API + path, body);
    }

    private JsonNode rule(String event, String extra) {
        HttpResponse<String> response = in("POST", "/notification-rules", """
                { "name": "r-%s", "event": "%s", "channel": "EMAIL", "target": "%s, ana@example.com"%s }
                """.formatted(UUID.randomUUID().toString().substring(0, 6), event, addressee, extra));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return json(response);
    }

    private String ingest(String env, String status) {
        UUID id = UUID.randomUUID();
        String payload = payloadAt(env, id, project, unique(env.toLowerCase()), uniqueUrl(), "update", status,
                Instant.now().minusSeconds(30), changeset("SUP-" + id.toString().substring(0, 4), "FAILED"));
        HttpResponse<String> response = call("POST", API + "/ingest/operations", token, payload);
        assertThat(response.statusCode()).as(response.body()).isIn(200, 202);
        return id.toString();
    }

    private long deliveries(String status) {
        return jdbc.sql("""
                select count(*) from notification_delivery d join notification_rule r on r.id = d.rule_id
                 where r.workspace_id = :ws and d.status = :status
                """).param("ws", workspaceId).param("status", status).query(Long.class).single();
    }

    // ------------------------------------------------------------------- rules

    @Test
    @DisplayName("a rule is validated: event, channel, addresses, https webhooks and a name")
    void validation() {
        String base = "\"name\":\"n\",\"event\":\"PRD_FAILURE\",\"channel\":\"EMAIL\",\"target\":\"a@b.com\"";
        assertThat(in("POST", "/notification-rules", "{" + base.replace("PRD_FAILURE", "NOPE") + "}").statusCode()).isEqualTo(422);
        assertThat(in("POST", "/notification-rules", "{" + base.replace("EMAIL", "FAX") + "}").statusCode()).isEqualTo(422);
        assertThat(in("POST", "/notification-rules", "{" + base.replace("a@b.com", "not-an-address") + "}").statusCode()).isEqualTo(422);
        assertThat(in("POST", "/notification-rules", "{" + base.replace("\"name\":\"n\"", "\"name\":\" \"") + "}").statusCode()).isEqualTo(422);
        assertThat(in("POST", "/notification-rules", """
                { "name": "t", "event": "PRD_FAILURE", "channel": "TEAMS", "target": "http://insecure.example/hook" }
                """).statusCode()).as("a webhook must be https").isEqualTo(422);
        assertThat(in("POST", "/notification-rules", "{" + base + ",\"environment\":\"NOPE\"}").statusCode()).isEqualTo(422);
    }

    @Test
    @DisplayName("a webhook URL is a credential: shown by host only, and kept when an update leaves it out")
    void webhookIsNeverReturned() {
        JsonNode created = json(in("POST", "/notification-rules", """
                { "name": "teams", "event": "GATE_BLOCKED", "channel": "TEAMS",
                  "target": "https://outlook.example.com/webhook/SECRET-TOKEN-123" }
                """));
        String id = created.get("id").asString();

        assertThat(created.get("target").asString()).isEqualTo("outlook.example.com/…");
        assertThat(in("GET", "/notification-rules", null).body()).doesNotContain("SECRET-TOKEN-123");

        HttpResponse<String> renamed = in("PUT", "/notification-rules/" + id, "{\"name\":\"teams-renamed\"}");
        assertThat(renamed.statusCode()).isEqualTo(200);
        String stored = jdbc.sql("select target from notification_rule where id = cast(:id as uuid)").param("id", id)
                .query(String.class).single();
        assertThat(stored).endsWith("SECRET-TOKEN-123");
        assertThat(renamed.body()).doesNotContain("SECRET-TOKEN-123");
    }

    @Test
    @DisplayName("rules can be listed, switched off and deleted, and each change is audited")
    void lifecycle() {
        JsonNode created = rule("PRD_FAILURE", "");
        String id = created.get("id").asString();

        assertThat(json(in("PUT", "/notification-rules/" + id, "{\"enabled\":false}")).get("enabled").asBoolean()).isFalse();
        assertThat(in("DELETE", "/notification-rules/" + id, null).statusCode()).isEqualTo(204);
        assertThat(json(in("GET", "/notification-rules", null))).isEmpty();

        List<String> events = jdbc.sql("select action from audit_event where target_id = :id order by seq").param("id", id)
                .query(String.class).list();
        assertThat(events).containsExactly("notification.create", "notification.update", "notification.delete");
    }

    // ------------------------------------------------------------------ events

    @Test
    @DisplayName("a failure in production queues a message to the rule, once, and the job delivers it")
    void productionFailure() {
        rule("PRD_FAILURE", "");

        String operation = ingest("PRD", "FAILURE");
        ingest("PRD", "FAILURE");

        assertThat(deliveries("PENDING")).isEqualTo(2);
        jobs.dispatch();
        assertThat(deliveries("SENT")).isEqualTo(2);
        assertThat(sentToMe()).hasSize(2);
        assertThat(sentToMe().get(0)).contains("\"to\":[\"" + addressee + "\",\"ana@example.com\"]", "Falha em producao");
        assertThat(operation).isNotBlank();
    }

    @Test
    @DisplayName("the same operation seen again is told once")
    void deduplicated() {
        rule("PRD_FAILURE", "");
        UUID id = UUID.randomUUID();
        String payload = payloadAt("PRD", id, project, unique("prd"), uniqueUrl(), "update", "FAILURE",
                Instant.now().minusSeconds(30), changeset("SUP-D", "FAILED"));

        call("POST", API + "/ingest/operations", token, payload);
        call("POST", API + "/ingest/operations", token, payload);

        assertThat(deliveries("PENDING")).isEqualTo(1);
    }

    @Test
    @DisplayName("a failure outside production does not fire PRD_FAILURE")
    void developmentFailureIsNotProduction() {
        rule("PRD_FAILURE", "");

        ingest("DEV", "FAILURE");

        assertThat(deliveries("PENDING")).isZero();
    }

    @Test
    @DisplayName("a blocked gate fires GATE_BLOCKED, and a rule scoped to an environment hears only that one")
    void gateBlockedAndScope() {
        rule("GATE_BLOCKED", ",\"environment\":\"PRD\"");

        ingest("DEV", "BLOCKED");
        assertThat(deliveries("PENDING")).as("DEV is not the rule's environment").isZero();

        ingest("PRD", "BLOCKED");
        assertThat(deliveries("PENDING")).isEqualTo(1);
    }

    @Test
    @DisplayName("a rule scoped to a project hears only that project")
    void projectScope() {
        callIn(workspace, "POST", API + "/projects", "{\"slug\":\"%s\",\"name\":\"P\"}".formatted(project));
        rule("GATE_BLOCKED", ",\"project\":\"" + project + "\"");

        ingest("PRD", "BLOCKED");
        assertThat(deliveries("PENDING")).isEqualTo(1);

        UUID other = UUID.randomUUID();
        call("POST", API + "/ingest/operations", token, payloadAt("PRD", other, unique("elsewhere"), unique("c"), uniqueUrl(),
                "update", "BLOCKED", Instant.now().minusSeconds(30), changeset("SUP-X", "FAILED")));
        assertThat(deliveries("PENDING")).as("another project").isEqualTo(1);
    }

    @Test
    @DisplayName("a disabled rule says nothing")
    void disabledRule() {
        JsonNode rule = rule("PRD_FAILURE", "");
        in("PUT", "/notification-rules/" + rule.get("id").asString(), "{\"enabled\":false}");

        ingest("PRD", "FAILURE");

        assertThat(deliveries("PENDING")).isZero();
    }

    @Test
    @DisplayName("a drift run with drift fires DRIFT_DETECTED; one without, nothing")
    void drift() {
        rule("DRIFT_DETECTED", "");
        ingest("PRD", "SUCCESS");
        UUID connection = jdbc.sql("select id from db_connection where workspace_id = :ws limit 1").param("ws", workspaceId)
                .query(UUID.class).single();
        UUID withDrift = driftRun(connection, true);
        UUID clean = driftRun(connection, false);

        listener.onOperation(driftEvent(withDrift, connection));
        listener.onOperation(driftEvent(clean, connection));

        assertThat(deliveries("PENDING")).isEqualTo(1);
    }

    private UUID driftRun(UUID connection, boolean hasDrift) {
        UUID operation = UUID.randomUUID();
        Instant at = Instant.now().minusSeconds(60);
        jdbc.sql("""
                insert into operation (id, workspace_id, db_connection_id, command, type, status, source, executed_by, started_at)
                values (:id, :ws, :c, 'diff', 'drift', 'SUCCESS', 'scheduler', 'scheduler', :at)
                """).param("id", operation).param("ws", workspaceId).param("c", connection).param("at", Timestamp.from(at)).update();
        jdbc.sql("""
                insert into drift_run (id, operation_id, db_connection_id, reference_type, status, has_drift, added, removed, modified, started_at)
                values (:id, :op, :c, 'SNAPSHOT', 'SUCCESS', :d, :a, 0, 0, :at)
                """).param("id", UUID.randomUUID()).param("op", operation).param("c", connection).param("d", hasDrift)
                .param("a", hasDrift ? 2 : 0).param("at", Timestamp.from(at)).update();
        return operation;
    }

    private OperationIngested driftEvent(UUID operation, UUID connection) {
        return new OperationIngested(operation, "diff", "SUCCESS", "drift", "scheduler", "scheduler", null, "alvo", "PRD", null,
                Instant.now(), 10L, false, workspaceId, connection);
    }

    @Test
    @DisplayName("an approved exception about to expire is announced once")
    void expiringException() {
        rule("EXCEPTION_EXPIRING", "");
        String check = "RollbackRequired";
        UUID checkId = jdbc.sql("select id from policy_check where code = :c and workspace_id is null").param("c", check)
                .query(UUID.class).single();
        UUID soon = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        for (Object[] row : new Object[][] {{soon, "2 days"}, {later, "20 days"}}) {
            jdbc.sql("""
                    insert into policy_exception (id, workspace_id, check_id, justification, requested_by, approved_by, status, expires_at)
                    values (:id, :ws, :check, 'justificativa longa o bastante', 'ana', 'bruno', 'APPROVED', now() + cast(:span as interval))
                    """).param("id", row[0]).param("ws", workspaceId).param("check", checkId).param("span", row[1]).update();
        }

        assertThat(jobs.announceExpiring()).isEqualTo(1);
        assertThat(jobs.announceExpiring()).as("already announced").isZero();
        assertThat(deliveries("PENDING")).isEqualTo(1);
    }

    @Test
    @DisplayName("an alert about the installation reaches every workspace that asked for it")
    void systemAlert() {
        rule("SYSTEM_ALERT", "");

        assertThat(notifications.notifyEveryWorkspace("SYSTEM_ALERT", "Trilha de auditoria adulterada", "corpo", "audit-broken:1"))
                .isGreaterThanOrEqualTo(1);
        assertThat(deliveries("PENDING")).isEqualTo(1);
    }

    // ----------------------------------------------------------------- delivery

    @Test
    @DisplayName("a delivery that fails is retried later, with the reason stored and without the endpoint's own words")
    void retries() {
        rule("PRD_FAILURE", "");
        ingest("PRD", "FAILURE");
        EMAIL.status = 500;

        jobs.dispatch();
        assertThat(sentToMe()).as("one attempt, which the service refused").hasSize(1);

        var row = jdbc.sql("""
                select d.status, d.attempts, d.last_error, d.next_at > now() as later from notification_delivery d
                  join notification_rule r on r.id = d.rule_id where r.workspace_id = :ws
                """).param("ws", workspaceId).query().singleRow();
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("attempts")).isEqualTo(1);
        assertThat((String) row.get("last_error")).isEqualTo("the endpoint answered 500");
        assertThat(row.get("later")).as("not retried at once").isEqualTo(true);

        // Not due yet: nothing goes out. Make it due, let the service recover, and it does.
        jobs.dispatch();
        assertThat(deliveries("SENT")).isZero();
        makeDue();
        EMAIL.status = 200;
        jobs.dispatch();
        assertThat(deliveries("SENT")).isEqualTo(1);
    }

    @Test
    @DisplayName("after five failures it gives up and shows as FAILED")
    void givesUp() {
        rule("PRD_FAILURE", "");
        ingest("PRD", "FAILURE");
        EMAIL.status = 503;

        for (int i = 0; i < NotificationJobs.MAX_ATTEMPTS; i++) {
            makeDue();
            jobs.dispatch();
        }

        assertThat(deliveries("FAILED")).isEqualTo(1);
        makeDue();
        EMAIL.status = 200;
        jobs.dispatch();
        assertThat(deliveries("SENT")).as("a failed delivery is not retried").isZero();
        JsonNode listed = json(in("GET", "/notification-deliveries?status=FAILED", null));
        assertThat(listed.get(0).get("attempts").asInt()).isEqualTo(NotificationJobs.MAX_ATTEMPTS);
        assertThat(listed.get(0).get("error").asString()).contains("503");
    }

    @Test
    @DisplayName("the test button sends a message now and says whether it arrived")
    void testButton() {
        JsonNode rule = rule("PRD_FAILURE", "");
        String path = "/notification-rules/" + rule.get("id").asString() + "/test";

        assertThat(json(in("POST", path, null)).get("ok").asBoolean()).isTrue();
        assertThat(sentToMe()).hasSize(1);

        EMAIL.status = 502;
        JsonNode failed = json(in("POST", path, null));
        assertThat(failed.get("ok").asBoolean()).isFalse();
        assertThat(failed.get("error").asString()).contains("502");
    }

    @Test
    @DisplayName("managing notifications needs workspace:admin")
    void needsPermission() {
        assertThat(callAs("nobody-" + UUID.randomUUID(), workspace, "GET", API + "/notification-rules", null).statusCode())
                .isEqualTo(403);
    }

    /** What the e-mail service received for THIS test's addressee. */
    private List<String> sentToMe() {
        return EMAIL.requests.stream().filter(r -> r.contains(addressee)).toList();
    }

    /** Makes this workspace's pending deliveries due, and only them. */
    private void makeDue() {
        jdbc.sql("""
                update notification_delivery set next_at = now() - interval '1 minute'
                 where status = 'PENDING' and rule_id in (select id from notification_rule where workspace_id = :ws)
                """).param("ws", workspaceId).update();
    }

    // ------------------------------------------------------- severity, silence, digest

    @Test
    @DisplayName("a rule with a minimum severity skips what is below it, and never skips an event that carries none")
    void severityFloor() {
        rule("GATE_BLOCKED", ", \"minSeverity\": 3");

        assertThat(notifications.notify(workspaceId, "GATE_BLOCKED", 2, project, "PRD", "s", "b", "k-low")).isZero();
        assertThat(notifications.notify(workspaceId, "GATE_BLOCKED", 4, project, "PRD", "s", "b", "k-high")).isEqualTo(1);
        assertThat(notifications.notify(workspaceId, "GATE_BLOCKED", null, project, "PRD", "s", "b", "k-none")).as("nothing to judge: told").isEqualTo(1);
    }

    @Test
    @DisplayName("quiet hours hold a message until the window ends, and outside the window it goes at once")
    void quietHours() {
        java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneId.of("UTC"));
        String inside = ", \"quietStart\": \"%s\", \"quietEnd\": \"%s\", \"quietZone\": \"UTC\"".formatted(
                now.minusHours(1).withSecond(0).withNano(0), now.plusHours(1).withSecond(0).withNano(0));
        JsonNode held = rule("DRIFT_DETECTED", inside);
        assertThat(held.get("quietZone").asString()).isEqualTo("UTC");

        assertThat(notifications.notify(workspaceId, "DRIFT_DETECTED", null, project, "DEV", "s", "b", "k-quiet")).isEqualTo(1);

        var row = jdbc.sql("select next_at > now() + interval '30 minutes' as later from notification_delivery where dedupe_key = 'k-quiet' and rule_id = cast(:id as uuid)")
                .param("id", held.get("id").asString()).query().singleRow();
        assertThat(row.get("later")).as("held for about an hour").isEqualTo(true);
        jobs.dispatch();
        assertThat(sentToMe()).as("nothing left during the quiet hours").isEmpty();

        in("DELETE", "/notification-rules/" + held.get("id").asString(), null);
        String outside = ", \"quietStart\": \"%s\", \"quietEnd\": \"%s\", \"quietZone\": \"UTC\"".formatted(
                now.plusHours(2).withSecond(0).withNano(0), now.plusHours(3).withSecond(0).withNano(0));
        rule("DRIFT_DETECTED", outside);
        notifications.notify(workspaceId, "DRIFT_DETECTED", null, project, "DEV", "s2", "b2", "k-open");
        jobs.dispatch();
        assertThat(sentToMe()).as("outside the window it goes").hasSize(1);
    }

    @Test
    @DisplayName("a digest rule speaks once for what piled up in its window")
    void digest() {
        JsonNode grouped = rule("DRIFT_DETECTED", ", \"digestMinutes\": 5");
        assertThat(grouped.get("digestMinutes").asInt()).isEqualTo(5);

        notifications.notify(workspaceId, "DRIFT_DETECTED", null, project, "DEV", "drift em A", "corpo A", "k-a");
        notifications.notify(workspaceId, "DRIFT_DETECTED", null, project, "DEV", "drift em B", "corpo B", "k-b");
        jobs.dispatch();
        assertThat(sentToMe()).as("still inside the window").isEmpty();

        jdbc.sql("update notification_delivery set next_at = now() - interval '1 minute' where rule_id = cast(:id as uuid)")
                .param("id", grouped.get("id").asString()).update();
        jobs.dispatch();

        assertThat(sentToMe()).hasSize(1);
        assertThat(sentToMe().get(0)).contains("2 notificacoes agrupadas", "drift em A", "drift em B");
        assertThat(deliveries("SENT")).isEqualTo(2);
    }

    @Test
    @DisplayName("the options are validated: a severity out of range, half a quiet window, a digest too short, an unknown zone")
    void optionsAreValidated() {
        String base = "\"name\":\"n\",\"event\":\"DRIFT_DETECTED\",\"channel\":\"EMAIL\",\"target\":\"a@b.com\"";
        assertThat(in("POST", "/notification-rules", "{" + base + ",\"minSeverity\":9}").statusCode()).isEqualTo(422);
        assertThat(in("POST", "/notification-rules", "{" + base + ",\"quietStart\":\"22:00\"}").statusCode()).isEqualTo(422);
        assertThat(in("POST", "/notification-rules", "{" + base + ",\"digestMinutes\":2}").statusCode()).isEqualTo(422);
        assertThat(in("POST", "/notification-rules", "{" + base + ",\"quietStart\":\"22:00\",\"quietEnd\":\"07:00\",\"quietZone\":\"Mars/Base\"}").statusCode()).isEqualTo(422);

        JsonNode ok = json(in("POST", "/notification-rules", "{" + base + ",\"minSeverity\":2,\"quietStart\":\"22:00\",\"quietEnd\":\"07:00\"}"));
        assertThat(ok.get("minSeverity").asInt()).isEqualTo(2);
        assertThat(ok.get("quietStart").asString()).isEqualTo("22:00");
        assertThat(ok.get("quietZone").asString()).as("a window without a zone is UTC").isEqualTo("UTC");
    }
}
