package io.github.carloslimajlle.liquimanager.client;

import java.nio.file.Path;
import java.time.Duration;

/**
 * How a collector reaches LiquiManager.
 *
 * @param url        base URL of LiquiManager; a trailing /api/v1 is accepted and dropped
 * @param token      service token ({@code lqm_...}); null means "not configured"
 * @param timeout    per request. Short on purpose: a collector never holds a deploy hostage (ADR-004)
 * @param bufferDir  where reports that could not be delivered wait for the next run; null disables the buffer
 */
public record LqmConfig(String url, String token, Duration timeout, Path bufferDir) {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    public LqmConfig {
        timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        if (url != null) {
            url = url.replaceAll("/+$", "");
            // The starter documents lqm.url WITH the API prefix; the client appends it itself.
            if (url.endsWith("/api/v1")) {
                url = url.substring(0, url.length() - "/api/v1".length());
            }
        }
    }

    /** A collector without a URL or a token has nowhere to report: it runs Liquibase and stays quiet. */
    public boolean isConfigured() {
        return url != null && !url.isBlank() && token != null && !token.isBlank();
    }
}
