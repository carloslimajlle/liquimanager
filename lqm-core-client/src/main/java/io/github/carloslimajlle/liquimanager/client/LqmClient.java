package io.github.carloslimajlle.liquimanager.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP client for the two things a collector asks of LiquiManager: "what policies apply
 * here" and "here is what happened".
 *
 * <p>Fail-open by construction (ADR-004). Nothing here throws to the caller for a network
 * error, a timeout, a 5xx or a malformed answer: {@link #effectivePolicies} returns empty
 * and {@link #report} buffers to disk and returns {@code false}. Only the caller, who knows
 * whether {@code lqm.enforce.failClosed} is on, may decide an unreachable server matters.
 *
 * <p>Uses {@code java.net.http} so nothing is imposed on a service embedding the starter.
 */
public final class LqmClient {

    private static final Logger LOG = Logger.getLogger(LqmClient.class.getName());
    private static final String API = "/api/v1";

    private final LqmConfig config;
    private final HttpClient http;
    private final DiskBuffer buffer;
    private final PolicyCache policyCache;

    public LqmClient(LqmConfig config) {
        this(config, java.time.Instant::now);
    }

    /** With the clock a test controls: the policy cache is about time. */
    LqmClient(LqmConfig config, java.util.function.Supplier<java.time.Instant> clock) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(config.timeout()).build();
        this.buffer = new DiskBuffer(config.bufferDir());
        this.policyCache = new PolicyCache(config.bufferDir(), clock);
    }

    public boolean isConfigured() {
        return config.isConfigured();
    }

    /**
     * The package to evaluate before a run. Empty when LiquiManager is unreachable, answers
     * with an error, or answers something this client cannot read.
     */
    public Optional<EffectivePolicies> effectivePolicies(String project, String env, String changelog) {
        return effectivePolicies(project, env, changelog, true);
    }

    /**
     * The same, with the cache of docs/07 § 1.2.1. An answer is reused for five minutes without asking; after that LiquiManager is asked
     * with the ETag of what is held, so an unchanged package costs a 304. When it cannot be reached, an answer up to 24 hours old stands
     * in for it (a stale gate is stricter than none) and the log says so.
     *
     * @param allowCache false for a caller that needs to know LiquiManager is up ({@code lqm.fail-closed}): it always asks, and a
     *                   failure is a failure, not a cached answer
     */
    public Optional<EffectivePolicies> effectivePolicies(String project, String env, String changelog, boolean allowCache) {
        if (!config.isConfigured()) {
            return Optional.empty();
        }
        String key = PolicyCache.key(project, env, changelog);
        java.time.Instant now = policyCache.now();
        Optional<PolicyCache.Entry> held = policyCache.get(key);
        if (allowCache && held.isPresent() && held.get().fresh(now)) {
            return Optional.of(held.get().policies());
        }
        StringBuilder query = new StringBuilder("?project=").append(enc(project)).append("&env=").append(enc(env));
        if (changelog != null && !changelog.isBlank()) {
            query.append("&changelog=").append(enc(changelog));
        }
        try {
            HttpRequest.Builder ask = request("/policies/effective" + query).GET();
            if (held.isPresent() && held.get().etag() != null) {
                ask.header("If-None-Match", held.get().etag());
            }
            HttpResponse<byte[]> response = http.send(ask.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() == 304 && held.isPresent()) {
                policyCache.put(key, held.get().etag(), held.get().policies(), now);
                return Optional.of(held.get().policies());
            }
            if (response.statusCode() != 200) {
                LOG.warning("lqm: policies/effective answered " + response.statusCode());
                return stale(held, allowCache, now);
            }
            EffectivePolicies policies = Json.MAPPER.readValue(response.body(), EffectivePolicies.class);
            policyCache.put(key, response.headers().firstValue("ETag").orElse(policies.etag()), policies, now);
            return Optional.of(policies);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "lqm: could not fetch policies: " + describe(e));
            return stale(held, allowCache, now);
        }
    }

    /** What stands in for an answer that did not come: the held one, if it is not too old and the caller accepts that; else nothing. */
    private Optional<EffectivePolicies> stale(Optional<PolicyCache.Entry> held, boolean allowCache, java.time.Instant now) {
        if (allowCache && held.isPresent() && held.get().usableWhenOffline(now)) {
            LOG.warning("lqm: using the policies from " + held.get().fetchedAt() + " (LiquiManager did not answer)");
            return Optional.of(held.get().policies());
        }
        LOG.warning("lqm: no policies available; running without a gate");
        return Optional.empty();
    }

    /**
     * What the stage before {@code env} already has, so a deploy can refuse to put in HML what is not in DEV (docs/08 and the
     * pipeline order). Empty when LiquiManager is unreachable or answers with an error: the caller decides what that means.
     */
    public Optional<PromotionGate> promotionGate(String project, String env) {
        if (!config.isConfigured()) {
            return Optional.empty();
        }
        try {
            HttpResponse<byte[]> response = http.send(request("/policies/promotion-gate?project=" + enc(project) + "&env=" + enc(env))
                    .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                LOG.warning("lqm: policies/promotion-gate answered " + response.statusCode());
                return Optional.empty();
            }
            return Optional.of(Json.MAPPER.readValue(response.body(), PromotionGate.class));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "lqm: could not ask for the promotion gate: " + describe(e));
            return Optional.empty();
        }
    }

    /**
     * Which of {@code items} already ran in {@code env} with another checksum, each with the lines that changed ({@code ChecksumChanged}).
     * Empty when LiquiManager cannot answer: the caller reads that as "nothing to report", never as a verdict.
     */
    public Optional<java.util.List<ChecksumChange>> checksumChanges(String project, String env, String commit, String changelog,
                                                                    java.util.List<ChecksumChange.Item> items) {
        if (!config.isConfigured()) {
            return Optional.empty();
        }
        try {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("project", project);
            body.put("env", env);
            body.put("commit", commit);
            body.put("changelog", changelog);
            body.put("changesets", items);
            HttpResponse<byte[]> response = http.send(request("/policies/checksum-check").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(body))).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                LOG.warning("lqm: policies/checksum-check answered " + response.statusCode());
                return Optional.empty();
            }
            return Optional.of(Json.MAPPER.readValue(response.body(), Json.MAPPER.getTypeFactory().constructCollectionType(java.util.List.class, ChecksumChange.class)));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "lqm: could not ask for changed checksums: " + describe(e));
            return Optional.empty();
        }
    }

    /**
     * Sends one operation report; on failure keeps it on disk for the next run.
     *
     * @return true when LiquiManager accepted it
     */
    public boolean report(IngestPayload payload) {
        if (!config.isConfigured()) {
            return false;
        }
        Delivery delivery = send(payload);
        if (delivery == Delivery.ACCEPTED) {
            flushBuffer();
            return true;
        }
        if (delivery == Delivery.REJECTED) {
            return false;
        }
        if (buffer.isEnabled()) {
            try {
                buffer.store(payload);
                LOG.warning("lqm: report " + payload.operationId() + " buffered; it is sent on the next run");
            } catch (IOException e) {
                LOG.log(Level.WARNING, "lqm: report " + payload.operationId() + " lost: " + e.getMessage());
            }
        }
        return false;
    }

    /** Re-sends what earlier runs could not deliver. Stops at the first failure: the server is still down. */
    public int flushBuffer() {
        if (!buffer.isEnabled()) {
            return 0;
        }
        int delivered = 0;
        try {
            List<Path> pending = buffer.pending();
            for (Path file : pending) {
                IngestPayload payload;
                try {
                    payload = buffer.read(file);
                } catch (IOException | RuntimeException e) {
                    // An unreadable file would block the queue forever.
                    LOG.warning("lqm: discarding unreadable buffered report " + file.getFileName());
                    buffer.discard(file);
                    continue;
                }
                Delivery delivery = send(payload);
                if (delivery == Delivery.RETRY) {
                    break;
                }
                buffer.discard(file);
                if (delivery == Delivery.ACCEPTED) {
                    delivered++;
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "lqm: could not read the buffer: " + e.getMessage());
        }
        return delivered;
    }

    // ------------------------------------------------------------------ runner

    /**
     * The next approved rollback, now this runner's; empty when there is none OR when LiquiManager cannot be reached.
     * A runner that cannot ask simply asks again later: nothing is lost by waiting.
     */
    public Optional<RunnerJob> claimRollback() {
        if (!config.isConfigured()) {
            return Optional.empty();
        }
        try {
            HttpResponse<byte[]> response = http.send(request("/runner/claim")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                if (response.statusCode() != 204) {
                    LOG.warning("lqm: runner/claim answered " + response.statusCode());
                }
                return Optional.empty();
            }
            return Optional.of(Json.MAPPER.readValue(response.body(), RunnerJob.class));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "lqm: could not claim a rollback: " + describe(e));
            return Optional.empty();
        }
    }

    /** Tells LiquiManager how the rollback went. False when it could not be told (the timeout job then fails it). */
    public boolean completeRollback(UUID id, boolean ok, UUID resultOperationId, String error) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", ok);
            if (resultOperationId != null) {
                body.put("resultOperationId", resultOperationId.toString());
            }
            if (error != null) {
                body.put("error", error);
            }
            HttpResponse<Void> response = http.send(request("/runner/requests/" + id + "/complete")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(Json.MAPPER.writeValueAsBytes(body))).build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "lqm: could not report the rollback result: " + describe(e));
            return false;
        }
    }

    /** ACCEPTED: stored. RETRY: try again later (server down, bad token). REJECTED: this payload will never be accepted. */
    private enum Delivery { ACCEPTED, RETRY, REJECTED }

    private Delivery send(IngestPayload payload) {
        try {
            byte[] body = Json.MAPPER.writeValueAsBytes(payload);
            HttpResponse<Void> response = http.send(
                    request("/ingest/operations").header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return Delivery.ACCEPTED;
            }
            LOG.warning("lqm: ingest answered " + status);
            // A wrong token (401/403), a timeout, throttling or a server error can heal; a 400/409/422 on
            // the same bytes never will, and keeping it would block the queue on every future run.
            boolean transientFailure = status >= 500 || status == 401 || status == 403 || status == 408 || status == 429;
            return transientFailure ? Delivery.RETRY : Delivery.REJECTED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Delivery.RETRY;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "lqm: could not reach LiquiManager: " + describe(e));
            return Delivery.RETRY;
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(config.url() + API + path))
                .timeout(config.timeout())
                .header("Authorization", "Bearer " + config.token())
                .header("Accept", "application/json");
    }

    private static String describe(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
