package io.craftrealm.forge.discord;

import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Optional outbound-only Discord webhook notifications; never handles inbound commands. */
public final class ForgeDiscordBridge implements AutoCloseable {
    private final String webhookUrl;
    private final Logger logger;
    private final ExecutorService executor;
    private final HttpClient client;
    private final AtomicLong lastWarningAt = new AtomicLong();
    private final boolean configured;

    public ForgeDiscordBridge(boolean enabled, String webhookUrl, Logger logger) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.logger = logger;
        this.executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "CraftRealm-Discord-Webhook");
            thread.setDaemon(true);
            return thread;
        });
        this.client = HttpClient.newBuilder().executor(executor)
                .connectTimeout(Duration.ofSeconds(5)).build();
        this.configured = enabled && validWebhook(this.webhookUrl);
        if (enabled && !configured) {
            logger.warn("Discord notifications are enabled but the webhook URL is invalid; expected an HTTPS Discord webhook URL.");
        }
    }

    public boolean configured() {
        return configured;
    }

    public void send(String message) {
        if (!configured || message == null || message.isBlank()) return;
        String content = message.length() > 1800 ? message.substring(0, 1800) : message;
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":" + quote(content)
                        + ",\"allowed_mentions\":{\"parse\":[]}}", StandardCharsets.UTF_8))
                .build();
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .whenComplete((response, failure) -> {
                    if (failure != null) warnLimited("Discord webhook delivery failed", failure);
                    else if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        warnLimited("Discord webhook returned HTTP " + response.statusCode(), null);
                    }
                });
    }

    private void warnLimited(String message, Throwable failure) {
        long now = System.currentTimeMillis();
        long previous = lastWarningAt.get();
        if (now - previous < 60_000L || !lastWarningAt.compareAndSet(previous, now)) return;
        if (failure == null) logger.warn("{}", message);
        else logger.warn("{}", message, failure);
    }

    private static boolean validWebhook(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && ("discord.com".equalsIgnoreCase(host) || "discordapp.com".equalsIgnoreCase(host))
                    && uri.getPath() != null && uri.getPath().startsWith("/api/webhooks/")
                    && uri.getUserInfo() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
