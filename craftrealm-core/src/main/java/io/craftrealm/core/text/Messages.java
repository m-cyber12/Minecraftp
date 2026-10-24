package io.craftrealm.core.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;

/**
 * The single place chat messages leave CraftRealm.
 *
 * <p>Every message carries the configured prefix, so the server keeps one visual
 * identity no matter which module produced the text.</p>
 */
public final class Messages {

    private volatile MessageBundle bundle;
    private volatile Component prefix;
    private volatile String prefixRaw;

    public Messages(MessageBundle bundle, String prefix) {
        this.bundle = bundle;
        this.prefixRaw = prefix == null ? "" : prefix;
        this.prefix = MessageBundle.parse(prefixRaw);
    }

    /** Hot-swaps the bundle, used by {@code /craftrealm reload}. */
    public void update(MessageBundle newBundle, String newPrefix) {
        this.bundle = newBundle;
        this.prefixRaw = newPrefix == null ? "" : newPrefix;
        this.prefix = MessageBundle.parse(prefixRaw);
    }

    public MessageBundle bundle() {
        return bundle;
    }

    public Component prefix() {
        return prefix;
    }

    public String raw(String key, Object... placeholders) {
        return bundle.raw(key, placeholders);
    }

    public Component get(String key, Object... placeholders) {
        return bundle.component(key, placeholders);
    }

    /** Prefix + message, on the same line. */
    public Component prefixed(String key, Object... placeholders) {
        return prefix.append(bundle.component(key, placeholders));
    }

    public void send(CommandSender sender, String key, Object... placeholders) {
        if (sender == null) {
            return;
        }
        sender.sendMessage(prefixed(key, placeholders));
    }

    /** A message without the prefix (for multi-line detail blocks). */
    public void sendRaw(CommandSender sender, String key, Object... placeholders) {
        if (sender != null) {
            sender.sendMessage(bundle.component(key, placeholders));
        }
    }

    public void broadcast(String key, Object... placeholders) {
        Bukkit.broadcast(prefixed(key, placeholders));
    }

    public void actionBar(Player player, String key, Object... placeholders) {
        if (player != null) {
            player.sendActionBar(bundle.component(key, placeholders));
        }
    }

    public void title(Player player, String titleKey, String subtitleKey, Object... placeholders) {
        if (player == null) {
            return;
        }
        player.showTitle(Title.title(
                bundle.component(titleKey, placeholders),
                bundle.component(subtitleKey, placeholders),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(600))));
    }

    /** Plain text for console logging and the web API. */
    public String plain(String key, Object... placeholders) {
        return MessageBundle.plain(bundle.component(key, placeholders));
    }

    public String prefixRaw() {
        return prefixRaw;
    }
}
