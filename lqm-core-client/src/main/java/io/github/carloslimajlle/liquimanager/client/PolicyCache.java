package io.github.carloslimajlle.liquimanager.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The effective policies a collector last got, kept so it does not ask LiquiManager again for five minutes (a deploy and its retries,
 * or a service restarting in a loop) and so it still has something to evaluate when LiquiManager cannot be reached (docs/07 § 1.2.1).
 *
 * <p>Two copies: in memory, and one file per (project, environment, changelog) under {@code <bufferDir>/policies}, which survives the
 * process. The file is written whole and moved into place, so a process killed halfway never leaves a half policy to be believed. What
 * is stale is only used when LiquiManager cannot answer, and not past {@link #MAX_STALE}: a policy that old is a different set of
 * rules by now. Whatever cannot be read back is ignored (the cache is an optimisation, never a reason to fail).
 */
final class PolicyCache {

    private static final Logger LOG = Logger.getLogger(PolicyCache.class.getName());

    /** How long an answer is used without asking again. */
    static final Duration TTL = Duration.ofMinutes(5);
    /** How old an answer may be and still stand in for an unreachable LiquiManager. */
    static final Duration MAX_STALE = Duration.ofHours(24);

    /** One answer: its ETag, the policies, and when it was fetched (or last confirmed by a 304). */
    record Entry(String etag, EffectivePolicies policies, Instant fetchedAt) {

        boolean fresh(Instant now) {
            return Duration.between(fetchedAt, now).compareTo(TTL) < 0;
        }

        boolean usableWhenOffline(Instant now) {
            return Duration.between(fetchedAt, now).compareTo(MAX_STALE) < 0;
        }
    }

    private final Map<String, Entry> memory = new ConcurrentHashMap<>();
    private final Path dir;
    private final Supplier<Instant> clock;

    PolicyCache(Path bufferDir, Supplier<Instant> clock) {
        this.dir = bufferDir == null ? null : bufferDir.resolve("policies");
        this.clock = clock;
    }

    Instant now() {
        return clock.get();
    }

    static String key(String project, String env, String changelog) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((nz(project) + "|" + nz(env) + "|" + nz(changelog)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    /** The latest answer for the key, from memory or from the file of an earlier run. */
    Optional<Entry> get(String key) {
        Entry cached = memory.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        if (dir == null) {
            return Optional.empty();
        }
        Path file = dir.resolve(key + ".json");
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            JsonNode node = Json.MAPPER.readTree(Files.readAllBytes(file));
            Entry entry = new Entry(node.path("etag").asText(null),
                    Json.MAPPER.treeToValue(node.path("policies"), EffectivePolicies.class), Instant.parse(node.path("fetchedAt").asText()));
            memory.put(key, entry);
            return Optional.of(entry);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "lqm: ignored an unreadable policy cache file: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Remembers an answer: in memory and, when there is a directory, in its file. */
    void put(String key, String etag, EffectivePolicies policies, Instant fetchedAt) {
        Entry entry = new Entry(etag, policies, fetchedAt);
        memory.put(key, entry);
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            ObjectNode node = Json.MAPPER.createObjectNode();
            node.put("etag", etag);
            node.put("fetchedAt", fetchedAt.toString());
            node.set("policies", Json.MAPPER.valueToTree(policies));
            Path temporary = Files.createTempFile(dir, key, ".tmp");
            Files.write(temporary, Json.MAPPER.writeValueAsBytes(node));
            Files.move(temporary, dir.resolve(key + ".json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.FINE, "lqm: could not write the policy cache: " + e.getMessage());
        }
    }
}
