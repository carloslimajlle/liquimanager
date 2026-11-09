package io.github.carloslimajlle.liquimanager.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.github.carloslimajlle.liquimanager.ingest.OperationIngested;

/**
 * Listens to every operation that gets ingested and decides whether anybody should hear about it.
 *
 * <p>The texts are in pt-BR on purpose: they are read by people (RNF-12). They are also free of accents because the
 * e-mail service and some webhooks mangle them; that is a choice of the messages, not of the interface.
 */
@Component
class NotificationListener {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationListener.class);

    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final String publicUrl;

    NotificationListener(JdbcClient jdbc, NotificationService notifications,
                         @Value("${lqm.public-url:}") String publicUrl) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    // REQUIRES_NEW: after the ingestion commits, the transaction it used is finished, and what is written here
    // would otherwise be written into it and never committed.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @org.springframework.transaction.annotation.Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    void onOperation(OperationIngested event) {
        try {
            var row = jdbc.sql("""
                    select o.status, o.gate_result, o.error_message, o.command, o.executed_by, e.is_production
                      from operation o join db_connection c on c.id = o.db_connection_id
                      join environment e on e.id = c.environment_id
                     where o.id = :id order by o.started_at desc limit 1
                    """).param("id", event.id()).query().listOfRows().stream().findFirst();
            if (row.isEmpty()) {
                return;
            }
            var op = row.get();
            String where = event.connection() + " (" + event.environment() + ")";
            String link = publicUrl.isEmpty() ? "" : "\n" + publicUrl + "/operations/" + event.id();

            if ("database-change".equals(event.type()) && "FAILURE".equals(op.get("status"))
                    && Boolean.TRUE.equals(op.get("is_production"))) {
                notifications.notify(event.workspaceId(), "PRD_FAILURE", event.project(), event.environment(),
                        "Falha em producao: " + event.command() + " em " + where,
                        event.command() + " falhou em " + where + ", executado por " + event.executedBy() + "."
                                + (op.get("error_message") == null ? "" : "\nErro: " + op.get("error_message")) + link,
                        "op:" + event.id() + ":failure");
            }
            if ("BLOCKED".equals(op.get("status")) || "BLOCKED".equals(op.get("gate_result"))) {
                Integer worst = jdbc.sql("select max(f.severity) from policy_finding f where f.operation_id = :id")
                        .param("id", event.id()).query(Integer.class).optional().orElse(null);
                notifications.notify(event.workspaceId(), "GATE_BLOCKED", worst, event.project(), event.environment(),
                        "Gate bloqueado: " + event.command() + " em " + where,
                        "As politicas bloquearam " + event.command() + " em " + where + ", executado por " + event.executedBy()
                                + ". Nada foi aplicado." + link,
                        "op:" + event.id() + ":blocked");
            }
            if ("drift".equals(event.type())) {
                jdbc.sql("select id, added, removed, modified from drift_run where operation_id = :id and has_drift")
                        .param("id", event.id()).query().listOfRows().forEach(run -> notifications.notify(
                                event.workspaceId(), "DRIFT_DETECTED", null, event.environment(),
                                "Drift detectado em " + where,
                                "O banco " + where + " divergiu do ultimo estado limpo: +" + run.get("added") + " -" + run.get("removed")
                                        + " ~" + run.get("modified") + "." + link,
                                "drift:" + run.get("id")));
            }
        } catch (RuntimeException e) {
            LOG.warn("could not evaluate notifications for operation {}: {}", event.id(), e.getMessage());
        }
    }
}
