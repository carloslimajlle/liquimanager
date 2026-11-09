package io.github.carloslimajlle.liquimanager.collector;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Where a deploy job gets the database credentials from, when they are not in its variables: {@code --secret-ref=vault:secret/data/lqm/core-hml}.
 * The job asks the secret provider itself, with the provider's own environment, so LiquiManager never sees, relays or stores them (RNF-06).
 *
 * <p>Forms, and the environment each one reads (the names the providers' own tools use, so a CI that already has them needs nothing new):
 * <ul>
 *   <li>{@code vault:<path>}: KV v1 or v2 ({@code VAULT_ADDR}, then {@code VAULT_TOKEN} or AppRole with {@code VAULT_ROLE_ID} and
 *       {@code VAULT_SECRET_ID}, {@code VAULT_AUTH_PATH}, and {@code VAULT_NAMESPACE}).
 *   <li>{@code consul:<key>}: the value is JSON ({@code CONSUL_HTTP_ADDR}, {@code CONSUL_HTTP_TOKEN}).
 *   <li>{@code azure:<secret>}: the value is JSON ({@code AZURE_KEYVAULT_URL}, {@code AZURE_KEYVAULT_TOKEN}).
 *   <li>{@code env:<PREFIX>}: {@code <PREFIX>_USERNAME} and {@code <PREFIX>_PASSWORD}.
 * </ul>
 * The secret holds {@code username} and {@code password}. Whatever goes wrong is a {@link Failure} that says which provider and what
 * status, never a value: it ends up in a CI log.
 */
public final class SecretRef {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What was resolved. {@code toString} hides the password so a stray log line leaks nothing. */
    public record Credentials(String username, String password) {
        @Override
        public String toString() {
            return "Credentials[username=" + username + ", password=****]";
        }
    }

    /** The reference could not be turned into credentials; the message is safe to print. */
    public static final class Failure extends RuntimeException {
        public Failure(String message) {
            super(message);
        }
    }

    private SecretRef() {
    }

    public static Credentials resolve(String reference, Map<String, String> env) {
        int colon = reference == null ? -1 : reference.indexOf(':');
        if (colon <= 0 || colon == reference.length() - 1) {
            throw new Failure("--secret-ref must be provider:reference (vault:, consul:, azure: or env:)");
        }
        String provider = reference.substring(0, colon).toLowerCase(Locale.ROOT);
        String path = reference.substring(colon + 1);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return switch (provider) {
            case "env" -> fromEnvironment(path, env);
            case "vault" -> fromVault(http, path, env);
            case "consul" -> fromConsul(http, path, env);
            case "azure" -> fromAzure(http, path, env);
            default -> throw new Failure("unknown secret provider " + provider + " (vault, consul, azure or env)");
        };
    }

    private static Credentials fromEnvironment(String prefix, Map<String, String> env) {
        String name = prefix.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        String user = env.get(name + "_USERNAME");
        String password = env.get(name + "_PASSWORD");
        if (user == null && password == null) {
            throw new Failure("env: no " + name + "_USERNAME or " + name + "_PASSWORD in the environment");
        }
        return new Credentials(user, password);
    }

    private static Credentials fromVault(HttpClient http, String path, Map<String, String> env) {
        String addr = required(env, "VAULT_ADDR", "vault");
        String namespace = env.get("VAULT_NAMESPACE");
        String token = env.get("VAULT_TOKEN");
        boolean appRole = notBlank(env.get("VAULT_ROLE_ID")) && notBlank(env.get("VAULT_SECRET_ID"));
        if (appRole) {
            token = vaultLogin(http, addr, namespace, env);
        } else if (!notBlank(token)) {
            throw new Failure("vault: set VAULT_TOKEN, or VAULT_ROLE_ID and VAULT_SECRET_ID");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(trim(addr) + "/v1/" + path.replaceAll("^/+|/+$", "")))
                .timeout(Duration.ofSeconds(10)).header("X-Vault-Token", token).GET();
        if (notBlank(namespace)) {
            request.header("X-Vault-Namespace", namespace);
        }
        JsonNode body = parse(send(http, request.build(), "vault"), "vault");
        // KV v2 nests the secret under data.data, KV v1 under data.
        JsonNode data = body.path("data").has("data") ? body.path("data").path("data") : body.path("data");
        return credentialsFrom(data, "vault");
    }

    private static String vaultLogin(HttpClient http, String addr, String namespace, Map<String, String> env) {
        String authPath = env.getOrDefault("VAULT_AUTH_PATH", "approle").replaceAll("^/+|/+$", "");
        String body;
        try {
            body = JSON.writeValueAsString(Map.of("role_id", env.get("VAULT_ROLE_ID"), "secret_id", env.get("VAULT_SECRET_ID")));
        } catch (IOException e) {
            throw new Failure("vault: could not build the login");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(trim(addr) + "/v1/auth/" + authPath + "/login"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (notBlank(namespace)) {
            request.header("X-Vault-Namespace", namespace);
        }
        String token = parse(send(http, request.build(), "vault login"), "vault").path("auth").path("client_token").asText(null);
        if (!notBlank(token)) {
            throw new Failure("vault: the login returned no token");
        }
        return token;
    }

    private static Credentials fromConsul(HttpClient http, String key, Map<String, String> env) {
        String addr = required(env, "CONSUL_HTTP_ADDR", "consul");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(trim(addr) + "/v1/kv/" + key.replaceAll("^/+|/+$", "") + "?raw"))
                .timeout(Duration.ofSeconds(10)).GET();
        if (notBlank(env.get("CONSUL_HTTP_TOKEN"))) {
            request.header("X-Consul-Token", env.get("CONSUL_HTTP_TOKEN"));
        }
        return credentialsFrom(parse(send(http, request.build(), "consul"), "consul"), "consul");
    }

    private static Credentials fromAzure(HttpClient http, String name, Map<String, String> env) {
        String url = required(env, "AZURE_KEYVAULT_URL", "azure");
        String token = required(env, "AZURE_KEYVAULT_TOKEN", "azure");
        HttpRequest request = HttpRequest.newBuilder(URI.create(trim(url) + "/secrets/" + URLEncoder.encode(name, StandardCharsets.UTF_8)
                + "?api-version=7.4")).timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).GET().build();
        JsonNode body = parse(send(http, request, "azure"), "azure");
        return credentialsFrom(parse(body.path("value").asText(""), "azure"), "azure");
    }

    // ---------------------------------------------------------------------- helpers

    private static String send(HttpClient http, HttpRequest request, String what) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // The status only: an error body can echo the path or, worse, a value.
                throw new Failure(what + " answered " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(what + " request interrupted");
        } catch (IOException | IllegalArgumentException e) {
            throw new Failure("could not reach " + what);
        }
    }

    private static JsonNode parse(String text, String provider) {
        try {
            return JSON.readTree(text);
        } catch (IOException | RuntimeException e) {
            throw new Failure(provider + " returned something that is not JSON");
        }
    }

    private static Credentials credentialsFrom(JsonNode node, String provider) {
        String user = node.path("username").asText(null);
        String password = node.path("password").asText(null);
        if (user == null && password == null) {
            throw new Failure(provider + " secret has no username or password");
        }
        return new Credentials(user, password);
    }

    private static String required(Map<String, String> env, String name, String provider) {
        String value = env.get(name);
        if (!notBlank(value)) {
            throw new Failure(provider + ": " + name + " is not set");
        }
        return value;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String trim(String url) {
        return url.replaceAll("/+$", "");
    }
}
