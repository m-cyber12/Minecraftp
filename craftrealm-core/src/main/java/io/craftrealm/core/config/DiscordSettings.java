package io.craftrealm.core.config;

/**
 * Discord webhook notifications.
 *
 * @param enabled        post to Discord at all
 * @param webhookUrl     channel webhook URL (keep it secret)
 * @param notifyBans     bans and tempbans
 * @param notifyReports  new player reports
 * @param notifyLarge    transfers above the currency threshold
 * @param largeThreshold Crowns threshold for "big transaction" notifications
 * @param notifyEvents   world event start/stop
 */
public record DiscordSettings(boolean enabled, String webhookUrl, boolean notifyBans,
                              boolean notifyReports, boolean notifyLarge, double largeThreshold,
                              boolean notifyEvents) {

    public static DiscordSettings disabled() {
        return new DiscordSettings(false, "", true, true, true, 100_000.0, true);
    }

    public boolean usable() {
        return enabled && webhookUrl != null && !webhookUrl.isBlank();
    }
}
