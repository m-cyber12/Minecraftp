package io.craftrealm.admin.staff;

import io.craftrealm.core.config.Settings;
import io.craftrealm.core.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Vanish and staff chat — the two small tools every staff team asks for first.
 *
 * <p>Vanish is done with Paper's per-player visibility API rather than a potion
 * effect: vanished staff are invisible to regular players, still visible to other
 * staff, and cannot be revealed by the tab list.</p>
 */
public final class StaffTools {

    private final Plugin plugin;
    private final Messages messages;
    private final Settings.StaffSettings staffSettings;

    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Set<UUID> staffChat = ConcurrentHashMap.newKeySet();

    public StaffTools(Plugin plugin, Messages messages, Settings.StaffSettings staffSettings) {
        this.plugin = plugin;
        this.messages = messages;
        this.staffSettings = staffSettings;
    }

    // ------------------------------------------------------------------ vanish

    public boolean isVanished(UUID uuid) {
        return vanished.contains(uuid);
    }

    public boolean toggleVanish(Player player) {
        if (vanished.remove(player.getUniqueId())) {
            showToEveryone(player);
            messages.send(player, "staff.vanish-off");
            return false;
        }
        vanished.add(player.getUniqueId());
        hideFromRegularPlayers(player);
        messages.send(player, "staff.vanish-on");
        return true;
    }

    /** Hides a vanished player from everybody without the staff permission. */
    private void hideFromRegularPlayers(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.hasPermission("craftrealm.staff.vanish.see") && !other.equals(player)) {
                other.hidePlayer(plugin, player);
            }
        }
    }

    private void showToEveryone(Player player) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            other.showPlayer(plugin, player);
        }
    }

    /** Called on join so a vanished player stays hidden for newcomers. */
    public void applyOnJoin(Player joining) {
        for (UUID uuid : vanished) {
            Player staff = Bukkit.getPlayer(uuid);
            if (staff != null && !joining.hasPermission("craftrealm.staff.vanish.see")) {
                joining.hidePlayer(plugin, staff);
            }
        }
    }

    public void clear(UUID uuid) {
        vanished.remove(uuid);
        staffChat.remove(uuid);
    }

    // -------------------------------------------------------------- staff chat

    public boolean staffChatEnabled(UUID uuid) {
        return staffChat.contains(uuid);
    }

    /** @return the new state */
    public boolean toggleStaffChat(UUID uuid) {
        if (staffChat.remove(uuid)) {
            return false;
        }
        staffChat.add(uuid);
        return true;
    }

    public void broadcastStaffChat(Player sender, String message) {
        String format = messages.raw("staff.chat-format", "player", sender.getName(), "message", message);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("craftrealm.staff.chat")) {
                online.sendMessage(io.craftrealm.core.text.MessageBundle.parse(format));
            }
        }
        io.craftrealm.core.CraftRealmCore.logger().info("[STAFF] " + sender.getName() + ": " + message);
    }

    public Settings.StaffSettings settings() {
        return staffSettings;
    }

    /** Heaviest tier the player holds, by configured weight. */
    public Settings.StaffTier tierOf(Player player) {
        Settings.StaffTier best = null;
        for (Settings.StaffTier tier : staffSettings.tiers()) {
            if (player.hasPermission(tier.permission())
                    && (best == null || tier.weight() > best.weight())) {
                best = tier;
            }
        }
        return best;
    }
}
