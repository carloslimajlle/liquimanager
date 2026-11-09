package io.github.carloslimajlle.liquimanager.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.carloslimajlle.liquimanager.common.UuidV7;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The append-only audit trail (RNF-09). Every row carries
 * {@code hash = SHA-256(prev_hash | canonical content)}, so changing or removing any past
 * row breaks every hash after it, and {@link #verify} says where.
 *
 * <p>The chain is ONE chain for the whole table, not one per workspace: a per-workspace chain
 * would let someone delete a workspace's tail without anything else noticing. The price is that
 * writers serialise on an advisory lock, which is acceptable for an audit trail (a few
 * writes per human action) and unacceptable for anything hot — do not audit ingestion.
 *
 * <p>The content that is hashed is the canonical form (keys sorted, no whitespace) of the
 * before/after documents, because the jsonb column does not preserve the text it was given.
 */
@Service
public class AuditService {

    /** Arbitrary but fixed: the advisory-lock key of the audit chain. */
    private static final long CHAIN_LOCK = 0x4C514D4155444954L;
    private static final String GENESIS = "0".repeat(64);
    private static final JsonMapper CANONICAL = JsonMapper.builder()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private final JdbcClient jdbc;

    public AuditService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The result of walking the chain. {@code brokenAt} is the first {@code seq} that does not verify. */
    public record Verification(boolean intact, long checked, Long brokenAt) {
    }

    /** The name of whoever is acting. A request with no authentication is the unauthenticated dev profile. */
    public static String actorOf(Authentication authentication) {
        return authentication == null || authentication.getName() == null ? "dev" : authentication.getName();
    }

    @Transactional
    public void record(UUID workspace, String actor, String action, String targetType, String targetId,
                       Object before, Object after) {
        jdbc.sql("select pg_advisory_xact_lock(:key)").param("key", CHAIN_LOCK).query().singleRow();

        String previous = jdbc.sql("select hash from audit_event order by seq desc limit 1")
                .query(String.class).optional().orElse(GENESIS);

        UUID id = UuidV7.next();
        // Microseconds: what timestamptz stores, so the value read back hashes the same.
        Instant at = Instant.now().truncatedTo(ChronoUnit.MICROS);
        String beforeJson = canonical(before);
        String afterJson = canonical(after);
        String hash = hash(previous, id, workspace, at, actor, "user", action, targetType, targetId,
                beforeJson, afterJson);

        jdbc.sql("""
                insert into audit_event (id, workspace_id, at, actor, actor_type, action, target_type, target_id,
                                         before, after, prev_hash, hash)
                values (:id, :ws, :at, :actor, 'user', :action, :tt, :tid,
                        cast(:before as jsonb), cast(:after as jsonb), :prev, :hash)
                """)
                .param("id", id).param("ws", workspace).param("at", Timestamp.from(at)).param("actor", actor)
                .param("action", action).param("tt", targetType).param("tid", targetId)
                .param("before", beforeJson).param("after", afterJson)
                .param("prev", previous).param("hash", hash)
                .update();
    }

    /** Re-computes every hash from the start. O(n); meant for the daily job and for an export. */
    @Transactional(readOnly = true)
    public Verification verify() {
        List<Row> rows = jdbc.sql("""
                select seq, id, workspace_id, at, actor, actor_type, action, target_type, target_id,
                       cast(before as text) as before, cast(after as text) as after, prev_hash, hash
                  from audit_event order by seq
                """)
                .query((rs, n) -> new Row(rs.getLong("seq"), rs.getObject("id", UUID.class),
                        rs.getObject("workspace_id", UUID.class), rs.getTimestamp("at").toInstant(),
                        rs.getString("actor"), rs.getString("actor_type"), rs.getString("action"),
                        rs.getString("target_type"), rs.getString("target_id"),
                        rs.getString("before"), rs.getString("after"),
                        rs.getString("prev_hash").trim(), rs.getString("hash").trim()))
                .list();

        String expectedPrevious = GENESIS;
        long checked = 0;
        for (Row row : rows) {
            String recomputed = hash(row.prev, row.id, row.workspace, row.at, row.actor, row.actorType,
                    row.action, row.targetType, row.targetId, normalise(row.before), normalise(row.after));
            if (!row.prev.equals(expectedPrevious) || !row.hash.equals(recomputed)) {
                return new Verification(false, checked, row.seq);
            }
            expectedPrevious = row.hash;
            checked++;
        }
        return new Verification(true, checked, null);
    }

    private record Row(long seq, UUID id, UUID workspace, Instant at, String actor, String actorType, String action,
                       String targetType, String targetId, String before, String after, String prev, String hash) {
    }

    // ---------------------------------------------------------------- hashing

    private static String canonical(Object value) {
        return value == null ? "null" : sorted(CANONICAL.valueToTree(value));
    }

    /** jsonb text back to the canonical form the hash was computed over. */
    private static String normalise(String jsonbText) {
        return jsonbText == null ? "null" : sorted(CANONICAL.readTree(jsonbText));
    }

    /**
     * Sorted keys at every depth. The ORDER_MAP_ENTRIES_BY_KEYS feature only orders a Map being
     * serialised, not a JSON tree, so the tree is turned into plain maps and lists first.
     */
    private static String sorted(JsonNode tree) {
        return CANONICAL.writeValueAsString(CANONICAL.treeToValue(tree, Object.class));
    }

    private static String hash(String previous, UUID id, UUID workspace, Instant at, String actor, String actorType,
                               String action, String targetType, String targetId, String before, String after) {
        String content = String.join("|", previous, String.valueOf(id), String.valueOf(workspace), at.toString(),
                actor, actorType, action, String.valueOf(targetType), String.valueOf(targetId), before, after);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JDK", e);
        }
    }
}
