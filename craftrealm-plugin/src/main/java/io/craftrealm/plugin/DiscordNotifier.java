package io.craftrealm.plugin;

import io.craftrealm.core.config.DiscordSettings;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.logging.Logger;

/**
 * Posts notifications to a Discord webhook.
 *
 * <p>Runs entirely on an HTTP client thread with a short timeout: a slow Discord must
 * never stall the server tick. Failures are logged once and otherwise ignored — a
 * broken webhook is not a reason to lose a ban record.</p>
 */
public final class DiscordNotifier {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private DiscordNotifier() {
    }

    public static void send(DiscordSettings settings, String message, Logger logger) {
        if (settings == null || !settings.usable() || message == null || message.isBlank()) {
            return;
        }
        String payload = "{\"content\":\"" + escape(message) + "\"}";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(settings.webhookUrl()))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(throwable -> {
                    logger.warning("Discord webhook failed: " + throwable.getMessage());
                    return null;
                });
    }

    /** Minimal JSON string escaping — Discord is the only consumer. */
    static String escape(String value) {
        StringBuilder builder = new StringBuilder(value.length() + 16);
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (character < 0x20) {
                        builder.append(String.format("\\u%04x", (int) character));
                    } else {
                        builder.append(character);
                    }
                }
            }
        }
        return builder.toString();
    }
}
