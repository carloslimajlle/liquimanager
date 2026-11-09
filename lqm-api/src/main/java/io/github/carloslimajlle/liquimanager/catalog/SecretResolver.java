package io.github.carloslimajlle.liquimanager.catalog;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import io.github.carloslimajlle.liquimanager.common.LqmException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a connection's secret reference into credentials, in memory, at the moment of use
 * (docs/09 § 5, RNF-06). The credentials are never written anywhere, never logged, and held in
 * memory for at most {@code lqm.secrets.cache-ttl} (5 minutes by default): long enough not to hammer
 * the vault on every collector call, short enough that a rotation takes effect on its own.
 *
 * <p>Providers:
 * <ul>
 *   <li>{@code NONE}: no credentials (integrated authentication, trust, local development).
 *   <li>{@code ENV}: development only. {@code secret_ref} names an environment variable prefix and
 *       {@code <PREFIX>_USERNAME} / {@code <PREFIX>_PASSWORD} hold the values.
 *   <li>{@code VAULT}: HashiCorp Vault KV. {@code secret_ref} is the API path, e.g.
 *       {@code secret/data/lqm/core-hml} (KV v2) or {@code secret/lqm/core-hml} (KV v1); the
 *       secret has the keys {@code username} and {@code password}. It authenticates with a static token
 *       ({@code lqm.secrets.vault.token}) or, for production, with AppRole ({@code role-id} and {@code secret-id}):
 *       the resolver logs in by itself, keeps the token until shortly before it expires and logs in again when
 *       Vault refuses it. {@code namespace} sets {@code X-Vault-Namespace} (Vault Enterprise / HCP).
 *   <li>{@code CONSUL}: Consul KV. {@code secret_ref} is the key; its value is JSON with
 *       {@code username} and {@code password}.
 *   <li>{@code AZURE_KV}: Azure Key Vault. {@code secret_ref} is the secret name; its value is JSON
 *       with {@code username} and {@code password}. The access token is supplied by configuration
 *       ({@code lqm.secrets.azure.token}); exchanging a managed identity for one is not implemented.
 * </ul>
 * Anything that goes wrong reaching a provider is LQM-050, and the message never includes a value.
 */
@Component
public class SecretResolver {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Credentials for one use. {@code toString} is redacted so a stray log line leaks nothing. */
    public record Credentials(String username, String password) {
        @Override
        public String toString() {
            return "Credentials[username=" + username + ", password=****]";
        }
    }

    private record Cached(Credentials credentials, Instant expires) {
    }

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final Duration ttl;
    private final String vaultUrl;
    private final String vaultToken;
    private final String vaultNamespace;
    private final String vaultRoleId;
    private final String vaultSecretId;
    private final String vaultAuthPath;
    private volatile String vaultLoginToken;
    private volatile Instant vaultLoginExpires = Instant.MIN;
    private final String consulUrl;
    private final String consulToken;
    private final String azureVaultUrl;
    private final String azureToken;

    SecretResolver(Duration ttl, String vaultUrl, String vaultToken, String consulUrl, String consulToken,
                   String azureVaultUrl, String azureToken) {
        this(ttl, vaultUrl, vaultToken, "", "", "", "approle", consulUrl, consulToken, azureVaultUrl, azureToken);
    }

    @Autowired
    SecretResolver(@Value("${lqm.secrets.cache-ttl:5m}") Duration ttl,
                   @Value("${lqm.secrets.vault.url:}") String vaultUrl,
                   @Value("${lqm.secrets.vault.token:}") String vaultToken,
                   @Value("${lqm.secrets.vault.namespace:}") String vaultNamespace,
                   @Value("${lqm.secrets.vault.role-id:}") String vaultRoleId,
                   @Value("${lqm.secrets.vault.secret-id:}") String vaultSecretId,
                   @Value("${lqm.secrets.vault.approle-path:approle}") String vaultAuthPath,
                   @Value("${lqm.secrets.consul.url:}") String consulUrl,
                   @Value("${lqm.secrets.consul.token:}") String consulToken,
                   @Value("${lqm.secrets.azure.vault-url:}") String azureVaultUrl,
                   @Value("${lqm.secrets.azure.token:}") String azureToken) {
        // The documented ceiling is five minutes: a longer value would quietly break the rule.
        this.ttl = ttl.compareTo(Duration.ofMinutes(5)) > 0 ? Duration.ofMinutes(5) : ttl;
        this.vaultUrl = vaultUrl;
        this.vaultToken = vaultToken;
        this.vaultNamespace = vaultNamespace;
        this.vaultRoleId = vaultRoleId;
        this.vaultSecretId = vaultSecretId;
        this.vaultAuthPath = trimSlashes(vaultAuthPath == null || vaultAuthPath.isBlank() ? "approle" : vaultAuthPath);
        this.consulUrl = consulUrl;
        this.consulToken = consulToken;
        this.azureVaultUrl = azureVaultUrl;
        this.azureToken = azureToken;
    }

    public Credentials resolve(String provider, String reference) {
        String effective = provider == null ? "NONE" : provider;
        if (effective.equals("NONE")) {
            return new Credentials(null, null);
        }
        if (effective.equals("ENV")) {
            return fromEnvironment(reference);
        }
        String key = effective + "|" + reference;
        Cached cached = cache.get(key);
        if (cached != null && cached.expires().isAfter(Instant.now())) {
            return cached.credentials();
        }
        Credentials fresh = switch (effective) {
            case "VAULT" -> fromVault(reference);
            case "CONSUL" -> fromConsul(reference);
            case "AZURE_KV" -> fromAzure(reference);
            default -> throw unavailable("secret provider " + effective + " is unknown");
        };
        cache.put(key, new Cached(fresh, Instant.now().plus(ttl)));
        return fresh;
    }

    /** Forgets what was cached, so a rotation announced by someone takes effect now. */
    public void evictAll() {
        cache.clear();
    }

    /** Whether a Vault login is held and still good; for tests and diagnostics, never the token itself. */
    boolean vaultLoginHeld() {
        return vaultLoginToken != null && vaultLoginExpires.isAfter(Instant.now());
    }

    // ------------------------------------------------------------------ providers

    private Credentials fromEnvironment(String reference) {
        if (reference == null || reference.isBlank()) {
            throw unavailable("ENV provider needs a secret reference");
        }
        String prefix = reference.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        String user = System.getenv(prefix + "_USERNAME");
        String password = System.getenv(prefix + "_PASSWORD");
        if (user == null && password == null) {
            throw unavailable("no credentials found for secret reference " + reference);
        }
        return new Credentials(user, password);
    }

    private Credentials fromVault(String reference) {
        requireConfigured(vaultUrl, "lqm.secrets.vault.url", "VAULT");
        requireReference(reference, "VAULT");
        JsonNode body = parse(vaultRead(vaultUrl + "/v1/" + trimSlashes(reference)), "VAULT");
        // KV v2 nests the secret under data.data, KV v1 under data.
        JsonNode data = body.path("data").has("data") ? body.path("data").path("data") : body.path("data");
        return credentialsFrom(data, "VAULT");
    }

    private boolean usesAppRole() {
        return vaultRoleId != null && !vaultRoleId.isBlank() && vaultSecretId != null && !vaultSecretId.isBlank();
    }

    /** Reads a path with the current token; when Vault refuses it (expired, revoked) and AppRole is in use, logs in again once. */
    private String vaultRead(String url) {
        HttpResponse<String> response = vaultSend(url, vaultTokenNow(false));
        if (response.statusCode() == 403 && usesAppRole()) {
            response = vaultSend(url, vaultTokenNow(true));
        }
        if (response.statusCode() != 200) {
            // The status only: a vault's error body can echo the path or, worse, a value.
            throw unavailable("VAULT answered " + response.statusCode());
        }
        return response.body();
    }

    private HttpResponse<String> vaultSend(String url, String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET();
        if (token != null && !token.isBlank()) {
            request.header("X-Vault-Token", token);
        }
        if (vaultNamespace != null && !vaultNamespace.isBlank()) {
            request.header("X-Vault-Namespace", vaultNamespace);
        }
        return send(request.build(), "VAULT");
    }

    /** The token to use: the static one, or the AppRole login (renewed shortly before it expires, or when {@code renew}). */
    private synchronized String vaultTokenNow(boolean renew) {
        if (!usesAppRole()) {
            return vaultToken;
        }
        if (!renew && vaultLoginToken != null && vaultLoginExpires.isAfter(Instant.now())) {
            return vaultLoginToken;
        }
        String payload = JSON.writeValueAsString(Map.of("role_id", vaultRoleId, "secret_id", vaultSecretId));
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(vaultUrl + "/v1/auth/" + vaultAuthPath + "/login"))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload));
        if (vaultNamespace != null && !vaultNamespace.isBlank()) {
            request.header("X-Vault-Namespace", vaultNamespace);
        }
        HttpResponse<String> response = send(request.build(), "VAULT");
        if (response.statusCode() != 200) {
            vaultLoginToken = null;
            throw unavailable("VAULT login answered " + response.statusCode());
        }
        JsonNode auth = parse(response.body(), "VAULT").path("auth");
        String token = auth.path("client_token").asString(null);
        if (token == null || token.isBlank()) {
            throw unavailable("VAULT login returned no token");
        }
        long lease = auth.path("lease_duration").asLong(0);
        // Renew at 80% of the lease so a request never carries a token about to die; no lease means it does not expire on its own.
        Duration usable = lease > 0 ? Duration.ofSeconds(Math.max(5, lease * 8 / 10)) : Duration.ofMinutes(30);
        vaultLoginToken = token;
        vaultLoginExpires = Instant.now().plus(usable);
        return token;
    }

    private HttpResponse<String> send(HttpRequest request, String provider) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(provider + " request interrupted");
        } catch (IOException | IllegalArgumentException e) {
            throw unavailable("could not reach " + provider);
        }
    }

    private Credentials fromConsul(String reference) {
        requireConfigured(consulUrl, "lqm.secrets.consul.url", "CONSUL");
        requireReference(reference, "CONSUL");
        String raw = getText(consulUrl + "/v1/kv/" + trimSlashes(reference) + "?raw", "X-Consul-Token", consulToken, "CONSUL");
        return credentialsFrom(parse(raw, "CONSUL"), "CONSUL");
    }

    private Credentials fromAzure(String reference) {
        requireConfigured(azureVaultUrl, "lqm.secrets.azure.vault-url", "AZURE_KV");
        requireReference(reference, "AZURE_KV");
        String url = azureVaultUrl + "/secrets/" + URLEncoder.encode(reference, StandardCharsets.UTF_8) + "?api-version=7.4";
        JsonNode body = get(url, "Authorization", "Bearer " + azureToken, "AZURE_KV");
        return credentialsFrom(parse(body.path("value").asString(""), "AZURE_KV"), "AZURE_KV");
    }

    // -------------------------------------------------------------------- helpers

    private JsonNode get(String url, String header, String headerValue, String provider) {
        return parse(getText(url, header, headerValue, provider), provider);
    }

    private String getText(String url, String header, String headerValue, String provider) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET();
            if (headerValue != null && !headerValue.isBlank()) {
                request.header(header, headerValue);
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // The status only: a vault's error body can echo the path or, worse, a value.
                throw unavailable(provider + " answered " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(provider + " request interrupted");
        } catch (IOException | IllegalArgumentException e) {
            throw unavailable("could not reach " + provider);
        }
    }

    private static JsonNode parse(String text, String provider) {
        try {
            return JSON.readTree(text);
        } catch (RuntimeException e) {
            throw unavailable(provider + " returned something that is not JSON");
        }
    }

    private static Credentials credentialsFrom(JsonNode node, String provider) {
        String user = node.path("username").asString(null);
        String password = node.path("password").asString(null);
        if (user == null && password == null) {
            throw unavailable(provider + " secret has no username or password");
        }
        return new Credentials(user, password);
    }

    private static void requireConfigured(String value, String property, String provider) {
        if (value == null || value.isBlank()) {
            throw unavailable(provider + " is not configured (" + property + ")");
        }
    }

    private static void requireReference(String reference, String provider) {
        if (reference == null || reference.isBlank()) {
            throw unavailable(provider + " needs a secret reference");
        }
    }

    private static String trimSlashes(String value) {
        return value.replaceAll("^/+|/+$", "");
    }

    private static LqmException unavailable(String message) {
        return new LqmException(HttpStatus.BAD_GATEWAY, "LQM-050", message);
    }
}
