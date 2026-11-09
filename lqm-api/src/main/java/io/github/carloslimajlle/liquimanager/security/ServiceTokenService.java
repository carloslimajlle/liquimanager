package io.github.carloslimajlle.liquimanager.security;

import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Service;

import io.github.carloslimajlle.liquimanager.common.LqmException;
import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * Issues and verifies service tokens {@code lqm_<prefix8>_<secret32>} (docs/09 § 1).
 *
 * <p>Only an Argon2id hash is stored (RNF-08). The plaintext exists exactly once, in
 * the return value of {@link #issue}. The 8-character prefix is stored in clear so a
 * request can be matched to its row without scanning every hash.
 */
@Service
public class ServiceTokenService {

    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long MAX_LIFETIME_DAYS = 365;

    private final JdbcClient jdbc;
    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public ServiceTokenService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** What the caller gets back on creation: the row id and the one-time plaintext. */
    public record Issued(UUID id, String token, String prefix) {
    }

    public Issued issue(UUID workspaceId, String name, List<String> scopes, List<UUID> projectIds,
                        Instant expiresAt, String createdBy) {
        if (expiresAt == null) {
            throw LqmException.invalid("a service token must have an expiry");
        }
        if (expiresAt.isAfter(Instant.now().plusSeconds(MAX_LIFETIME_DAYS * 86_400))) {
            throw LqmException.invalid("a service token may live at most " + MAX_LIFETIME_DAYS + " days");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw LqmException.invalid("at least one scope is required");
        }

        String prefix = random(8);
        String secret = random(32);
        UUID id = UuidV7.next();

        jdbc.sql("""
                insert into service_token (id, workspace_id, name, token_hash, prefix, scopes,
                                           project_ids, expires_at, created_by)
                values (:id, :ws, :name, :hash, :prefix, :scopes, :projects, :expires, :by)
                """)
                .param("id", id)
                .param("ws", workspaceId)
                .param("name", name)
                .param("hash", encoder.encode(secret))
                .param("prefix", prefix)
                .param("scopes", scopes.toArray(String[]::new))
                .param("projects", projectIds == null ? null : projectIds.toArray(UUID[]::new))
                .param("expires", Timestamp.from(expiresAt))
                .param("by", createdBy)
                .update();

        return new Issued(id, "lqm_" + prefix + "_" + secret, prefix);
    }

    /**
     * Verifies a raw token. Empty for anything wrong — unknown prefix, bad secret,
     * revoked, expired — without saying which, so a probe learns nothing.
     */
    public Optional<ServiceToken> authenticate(String raw) {
        if (raw == null || !raw.startsWith("lqm_") || raw.length() != 4 + 8 + 1 + 32 || raw.charAt(12) != '_') {
            return Optional.empty();
        }
        String prefix = raw.substring(4, 12);
        String secret = raw.substring(13);

        return jdbc.sql("""
                select id, workspace_id, name, token_hash, scopes, project_ids, expires_at, revoked_at
                  from service_token where prefix = :prefix
                """)
                .param("prefix", prefix)
                .query((rs, n) -> {
                    if (rs.getTimestamp("revoked_at") != null) {
                        return null;
                    }
                    Timestamp expires = rs.getTimestamp("expires_at");
                    if (expires != null && expires.toInstant().isBefore(Instant.now())) {
                        return null;
                    }
                    if (!encoder.matches(secret, rs.getString("token_hash"))) {
                        return null;
                    }
                    return new ServiceToken(
                            rs.getObject("id", UUID.class),
                            rs.getObject("workspace_id", UUID.class),
                            rs.getString("name"),
                            toList(rs.getArray("scopes")),
                            toUuidList(rs.getArray("project_ids")));
                })
                .optional()
                .map(t -> {
                    touch(t.id());
                    return t;
                });
    }

    /** What the management screen shows about a token. Never the hash, never the secret. */
    public record Summary(UUID id, String name, String prefix, List<String> scopes, List<UUID> projectIds,
                          Instant expiresAt, Instant lastUsedAt, String createdBy, Instant revokedAt) {
    }

    public List<Summary> list(UUID workspaceId) {
        return jdbc.sql("""
                select id, name, prefix, scopes, project_ids, expires_at, last_used_at, created_by, revoked_at
                  from service_token where workspace_id = :ws order by name
                """)
                .param("ws", workspaceId)
                .query((rs, n) -> new Summary(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("prefix"),
                        toList(rs.getArray("scopes")), toUuidList(rs.getArray("project_ids")),
                        instant(rs.getTimestamp("expires_at")), instant(rs.getTimestamp("last_used_at")),
                        rs.getString("created_by"), instant(rs.getTimestamp("revoked_at"))))
                .list();
    }

    /** Revocation is a timestamp, not a delete: the row stays so audits can still name the token. */
    public boolean revoke(UUID workspaceId, UUID id) {
        return jdbc.sql("update service_token set revoked_at = now() "
                        + "where id = :id and workspace_id = :ws and revoked_at is null")
                .param("id", id).param("ws", workspaceId).update() > 0;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private void touch(UUID id) {
        jdbc.sql("update service_token set last_used_at = now() where id = :id").param("id", id).update();
    }

    private static List<String> toList(Array array) throws java.sql.SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static List<UUID> toUuidList(Array array) throws java.sql.SQLException {
        return array == null ? List.of() : Arrays.asList((UUID[]) array.getArray());
    }

    private static String random(int length) {
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            out.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return out.toString();
    }
}
