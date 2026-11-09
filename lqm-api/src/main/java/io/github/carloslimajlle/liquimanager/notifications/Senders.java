package io.github.carloslimajlle.liquimanager.notifications;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * The three channels. Each is a single HTTP POST with a short timeout; anything but a 2xx is a failure carrying the
 * status only (a webhook can echo the URL, which is a credential).
 *
 * <ul>
 *   <li>EMAIL: the internal e-mail service, at {@code lqm.notifications.email.url}, as
 *       {@code {"to": [...], "subject": "...", "body": "..."}}. That is the shape this code ASSUMES for the
 *       service; its real contract was not available, so adapting it is one method.
 *   <li>TEAMS: an incoming webhook, as a message card.
 *   <li>SLACK: an incoming webhook, as {@code {"text": "..."}}.
 * </ul>
 */
@Component
class Senders {

    /** A delivery that could not be made, with a reason safe to store and show. */
    static final class Failed extends Exception {
        Failed(String message) {
            super(message);
        }
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String emailUrl;
    private final String emailToken;

    Senders(@Value("${lqm.notifications.email.url:}") String emailUrl,
            @Value("${lqm.notifications.email.token:}") String emailToken) {
        this.emailUrl = emailUrl;
        this.emailToken = emailToken;
    }

    void send(String channel, String target, String subject, String body) throws Failed {
        switch (channel) {
            case "EMAIL" -> email(target, subject, body);
            case "TEAMS" -> post(target, Map.of("@type", "MessageCard", "@context", "https://schema.org/extensions",
                    "summary", subject, "title", subject, "text", body.replace("\n", "\n\n")), null);
            case "SLACK" -> post(target, Map.of("text", "*" + subject + "*\n" + body), null);
            default -> throw new Failed("unknown channel " + channel);
        }
    }

    private void email(String addresses, String subject, String body) throws Failed {
        if (emailUrl == null || emailUrl.isBlank()) {
            throw new Failed("the e-mail service is not configured (lqm.notifications.email.url)");
        }
        List<String> to = Arrays.stream(addresses.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        post(emailUrl, Map.of("to", to, "subject", subject, "body", body), emailToken);
    }

    private void post(String url, Object payload, String bearer) throws Failed {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload)));
            if (bearer != null && !bearer.isBlank()) {
                request.header("Authorization", "Bearer " + bearer);
            }
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new Failed("the endpoint answered " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed("interrupted");
        } catch (IOException | IllegalArgumentException e) {
            throw new Failed("could not reach the endpoint");
        }
    }
}
