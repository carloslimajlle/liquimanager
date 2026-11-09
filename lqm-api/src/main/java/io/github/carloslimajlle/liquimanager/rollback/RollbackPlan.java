package io.github.carloslimajlle.liquimanager.rollback;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.common.LqmException;

/**
 * What "go back to this changeset" would undo on a connection: that changeset and every changeset applied after it, newest first,
 * which is the order Liquibase itself undoes things in ({@code rollbackCount}). A changeset in the middle is never undone alone: a
 * later one may stand on it (a column on a table), and undoing only the table would leave the column's changeset recorded as
 * applied over something that is gone.
 *
 * <p>The order is the one LiquiManager knows (when each applied changeset was last executed there). The runner checks it against
 * the database's own history before it undoes anything and refuses, undoing nothing, if the two disagree.
 */
@Component
class RollbackPlan {

    /** One changeset to undo. {@code operationId} is the operation that last applied it on this connection. */
    record Item(UUID uuid, String changesetId, String author, String filePath, boolean hasRollback, Object deployedAt, UUID operationId,
                boolean target) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("changesetUuid", uuid);
            m.put("changesetId", changesetId);
            m.put("author", author);
            m.put("filePath", filePath);
            m.put("hasRollback", hasRollback);
            m.put("deployedAt", deployedAt instanceof Timestamp t ? t.toInstant() : deployedAt);
            m.put("operationId", operationId);
            m.put("target", target);
            return m;
        }
    }

    private final JdbcClient jdbc;

    RollbackPlan(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What is applied on the connection now, newest first: where a person picks the changeset to go back to. */
    List<Item> applied(UUID connectionId) {
        List<Map<String, Object>> rows = appliedRows(connectionId);
        List<Item> items = new ArrayList<>();
        for (int i = rows.size() - 1; i >= 0; i--) {
            items.add(item(rows.get(i), false));
        }
        return items;
    }

    private static Item item(Map<String, Object> row, boolean target) {
        return new Item((UUID) row.get("id"), (String) row.get("cs_id"), (String) row.get("author"), (String) row.get("file_path"),
                Boolean.TRUE.equals(row.get("has_rollback")), row.get("deployed_at"), (UUID) row.get("last_operation_id"), target);
    }

    private List<Map<String, Object>> appliedRows(UUID connectionId) {
        return jdbc.sql("""
                select cs.id, cs.cs_id, cs.author, cs.file_path, cs.has_rollback, st.deployed_at, st.last_operation_id
                  from changeset_state st
                  join changeset cs on cs.id = st.changeset_id
                  left join operation_changeset oc on oc.operation_id = st.last_operation_id and oc.changeset_id = st.changeset_id
                 where st.db_connection_id = :c and st.status = 'EXECUTED'
                 order by st.deployed_at nulls first, oc.exec_order nulls first, cs.first_seen_at, cs.cs_id
                """).param("c", connectionId).query().listOfRows();
    }

    /** What would be undone to go back to {@code target}, newest first; the target is the last item. */
    List<Item> to(UUID connectionId, UUID target) {
        List<Map<String, Object>> applied = appliedRows(connectionId);
        int from = -1;
        for (int i = 0; i < applied.size(); i++) {
            if (target.equals(applied.get(i).get("id"))) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            throw LqmException.invalid("that changeset is not applied on this connection (anymore), so there is nothing to go back to");
        }
        List<Item> items = new ArrayList<>();
        for (int i = applied.size() - 1; i >= from; i--) {
            items.add(item(applied.get(i), i == from));
        }
        return items;
    }

    /** The changesets in the list that cannot be undone. */
    static List<String> withoutRollback(List<Item> items) {
        return items.stream().filter(item -> !item.hasRollback()).map(Item::changesetId).toList();
    }
}
