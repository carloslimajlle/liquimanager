package io.github.carloslimajlle.liquimanager.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import io.github.carloslimajlle.liquimanager.common.UuidV7;

/**
 * The local emergency account (docs/09 § 1): for when the identity provider is down and somebody still has to act. It is off unless
 * {@code lqm.security.break-glass.enabled=true}, there is exactly one account, and signing in takes three things at once: the name, the
 * password (a hash in configuration) and a TOTP code from an authenticator app.
 *
 * <p>What keeps it from being a back door:
 * <ul>
 *   <li>Five wrong attempts in a row lock it for fifteen minutes, whoever is trying; the correct credentials do not help while locked.
 *   <li>A TOTP code works once: the last step accepted is kept, and a step at or before it is refused.
 *   <li>A session is an opaque token kept only as a hash, lives {@code lqm.security.break-glass.ttl} (an hour; never more than 24 h)
 *       and can be revoked.
 *   <li>Every sign-in, failure and request made with it is audited by the caller ({@link BreakGlassController},
 *       {@link BreakGlassAudit}).
 * </ul>
 * It answers every failure the same way, so an attacker learns nothing about which of the three was wrong.
 */
@Service
public class BreakGlassService {

    public static final String PREFIX = "lqm_bg_";
    static final int MAX_FAILURES = 5;
    static final Duration LOCKOUT = Duration.ofMinutes(15);
    static final Duration MAX_TTL = Duration.ofHours(24);
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** What a successful sign-in hands back: the one-time plaintext and when it stops working. */
    public record Issued(String token, Instant expiresAt) {
    }

    /** How a sign-in went. Only {@code SIGNED_IN} carries a session. */
    public enum Outcome { SIGNED_IN, REFUSED, LOCKED }

    public record Attempt(Outcome outcome, Issued issued) {
    }

    private final JdbcClient jdbc;
    private final PasswordEncoder passwords = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final boolean enabled;
    private final String username;
    private final String passwordHash;
    private final String totpSecret;
    private final Duration ttl;

    BreakGlassService(JdbcClient jdbc,
                      @Value("${lqm.security.break-glass.enabled:false}") boolean enabled,
                      @Value("${lqm.security.break-glass.username:}") String username,
                      @Value("${lqm.security.break-glass.password-hash:}") String passwordHash,
                      @Value("${lqm.security.break-glass.totp-secret:}") String totpSecret,
                      @Value("${lqm.security.break-glass.ttl:1h}") Duration ttl) {
        this.jdbc = jdbc;
        this.enabled = enabled;
        this.username = username;
        this.passwordHash = passwordHash;
        this.totpSecret = totpSecret;
        this.ttl = ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
        if (enabled) {
            // A security feature that is switched on and half configured must not start: it would look protected and not be.
            if (username.isBlank() || passwordHash.isBlank() || totpSecret.isBlank()) {
                throw new IllegalStateException("lqm.security.break-glass is enabled but username, password-hash or totp-secret is missing");
            }
            if (Totp.decode(totpSecret).length < 10) {
                throw new IllegalStateException("lqm.security.break-glass.totp-secret is too short (at least 80 bits)");
            }
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public String username() {
        return username;
    }

    /** One sign-in attempt. Never throws for a wrong credential: the caller audits and answers. */
    public Attempt signIn(String name, String password, String code, String clientIp, Instant now) {
        jdbc.sql("insert into break_glass_state (id) values (1) on conflict do nothing").update();
        Optional<Instant> lockedUntil = jdbc.sql("select locked_until from break_glass_state where id = 1")
                .query((rs, n) -> rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant())
                .optional().filter(until -> until.isAfter(now));
        if (lockedUntil.isPresent()) {
            return new Attempt(Outcome.LOCKED, null);
        }

        // All three are always evaluated, so how long the answer takes does not say which one was wrong.
        boolean nameOk = Totp.constantTimeEquals(name == null ? "" : name, username);
        boolean passwordOk = password != null && passwords.matches(password, passwordHash);
        long step = Totp.matchingStep(totpSecret, code, now);
        if (nameOk && passwordOk && step >= 0 && acceptStep(step)) {
            return new Attempt(Outcome.SIGNED_IN, openSession(clientIp, now));
        }
        jdbc.sql("""
                update break_glass_state
                   set failed_attempts = case when failed_attempts + 1 >= :max then 0 else failed_attempts + 1 end,
                       locked_until = case when failed_attempts + 1 >= :max then :until else locked_until end
                 where id = 1
                """).param("max", MAX_FAILURES).param("until", java.sql.Timestamp.from(now.plus(LOCKOUT))).update();
        return new Attempt(Outcome.REFUSED, null);
    }

    /** Takes the step once: the update only matches when it is newer than the last one, so a second use, even a simultaneous one, loses. */
    private boolean acceptStep(long step) {
        return jdbc.sql("update break_glass_state set last_totp_step = :step, failed_attempts = 0, locked_until = null "
                + "where id = 1 and last_totp_step < :step").param("step", step).update() == 1;
    }

    private Issued openSession(String clientIp, Instant now) {
        StringBuilder secret = new StringBuilder(PREFIX);
        for (int i = 0; i < 48; i++) {
            secret.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        String token = secret.toString();
        Instant expires = now.plus(ttl);
        jdbc.sql("insert into break_glass_session (id, token_hash, expires_at, client_ip) values (:id, :hash, :expires, :ip)")
                .param("id", UuidV7.next()).param("hash", sha256(token)).param("expires", java.sql.Timestamp.from(expires))
                .param("ip", clientIp == null ? null : clientIp.substring(0, Math.min(64, clientIp.length()))).update();
        return new Issued(token, expires);
    }

    /** The session a token opens, if it is still good: not expired, not revoked, and the account still enabled. */
    public Optional<UUID> authenticate(String token) {
        if (!enabled || token == null || !token.startsWith(PREFIX)) {
            return Optional.empty();
        }
        return jdbc.sql("select id from break_glass_session where token_hash = :hash and revoked_at is null and expires_at > now()")
                .param("hash", sha256(token)).query(UUID.class).optional();
    }

    public void revoke(String token) {
        jdbc.sql("update break_glass_session set revoked_at = now() where token_hash = :hash and revoked_at is null")
                .param("hash", sha256(token)).update();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
