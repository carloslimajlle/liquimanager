package io.github.carloslimajlle.liquimanager.matrix;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import io.github.carloslimajlle.liquimanager.audit.AuditService;
import io.github.carloslimajlle.liquimanager.common.UuidV7;
import io.github.carloslimajlle.liquimanager.ingest.OperationIngested;
import io.github.carloslimajlle.liquimanager.pending.PendingBroadcast;
import io.github.carloslimajlle.liquimanager.stream.OperationStream;

/**
 * Opens the approval request by itself. When a deploy that executed changesets is reported for a stage, the stage right after it
 * in the same pipeline, if it requires approval and has {@code auto_request} on, gets a {@code REQUESTED} promotion in the name of
 * {@code system}. Nothing is fired: a second person still decides (SoD-2), so the request is only the person's reminder, not a
 * shortcut. One open request per stage: a second deploy does not pile up requests.
 */
@Component
class AutoPromotionRequests {

    private static final Logger LOG = LoggerFactory.getLogger(AutoPromotionRequests.class);
    static final String ACTOR = "system";

    private final JdbcClient jdbc;
    private final Promotions promotions;
    private final AuditService audit;
    private final OperationStream stream;

    AutoPromotionRequests(JdbcClient jdbc, Promotions promotions, AuditService audit, OperationStream stream) {
        this.stream = stream;
        this.jdbc = jdbc;
        this.promotions = promotions;
        this.audit = audit;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onIngested(OperationIngested event) {
        if (!"database-change".equals(event.type()) || !"SUCCESS".equals(event.status()) || event.connectionId() == null) {
            return;
        }
        try {
            open(event);
        } catch (RuntimeException e) {
            // A reminder must never undo or fail what was reported.
            LOG.warn("auto request for operation {} failed: {}", event.id(), e.toString());
        }
    }

    private void open(OperationIngested event) {
        Long executed = jdbc.sql("select count_executed from operation where id = :id").param("id", event.id())
                .query(Long.class).optional().orElse(0L);
        if (executed == null || executed == 0) {
            return;
        }
        List<Map<String, Object>> targets = jdbc.sql("""
                select nxt.id as stage_id, nxt.pipeline_id, e.code
                  from pipeline_stage cur
                  join pipeline_stage nxt on nxt.pipeline_id = cur.pipeline_id
                       and nxt.sort_order = (select min(x.sort_order) from pipeline_stage x
                                              where x.pipeline_id = cur.pipeline_id and x.sort_order > cur.sort_order)
                  join environment e on e.id = nxt.environment_id
                 where cur.db_connection_id = :conn and nxt.requires_approval and nxt.auto_request
                   and not exists (select 1 from promotion p where p.stage_id = nxt.id and p.status = 'REQUESTED')
                """).param("conn", event.connectionId()).query().listOfRows();
        for (Map<String, Object> target : targets) {
            UUID id = UuidV7.next();
            promotions.insertRequested(event.workspaceId(), (UUID) target.get("pipeline_id"), (UUID) target.get("stage_id"),
                    (String) target.get("code"), event.release(), ACTOR, id);
            audit.record(event.workspaceId(), ACTOR, "pipeline.promote.request", "promotion", id.toString(), null,
                    promotions.find(event.workspaceId(), id));
        }
        if (!targets.isEmpty()) {
            stream.broadcast(PendingBroadcast.EVENT);
        }
    }
}
