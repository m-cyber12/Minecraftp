package io.craftrealm.admin.listener;

import io.craftrealm.admin.punish.PunishmentService;
import io.craftrealm.admin.staff.StaffTools;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Locale;

/**
 * Enforces bans, mutes and freezes.
 *
 * <p>The pre-login check runs on Paper's asynchronous login thread, which is the
 * one place where a blocking database read is acceptable — and necessary, because
 * a banned player must be refused before a {@code Player} object exists.</p>
 */
public final class ModerationListener implements Listener {

    private final PunishmentService punishments;
    private final StaffTools staffTools;
    private final Messages messages;
    private final Settings.AdminSettings adminSettings;
    private final io.craftrealm.core.repo.PlayerRepository players;
    private final java.util.function.Consumer<String> discordHook;

    public ModerationListener(PunishmentService punishments, StaffTools staffTools, Messages messages,
                              Settings.AdminSettings adminSettings,
                              io.craftrealm.core.repo.PlayerRepository players,
                              java.util.function.Consumer<String> discordHook) {
        this.punishments = punishments;
        this.staffTools = staffTools;
        this.messages = messages;
        this.adminSettings = adminSettings;
        this.players = players;
        this.discordHook = discordHook == null ? message -> {
        } : discordHook;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (punishments.isBanned(event.getUniqueId())) {
            String name = event.getName();
            discordHook.accept("**ban check** " + name + " attempted to join while banned ("
                    + event.getUniqueId() + ")");
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    messages.prefixed("punish.banned-screen", "reason", punishments.defaultBanReason(),
                            "appeal", "contact staff"));
            return;
        }
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        punishments.load(player.getUniqueId());
        staffTools.applyOnJoin(player);
        players.touch(player.getUniqueId(), player.getName());
        String address = player.getAddress() == null ? null : player.getAddress().getHostAddress();
        if (address != null) {
            players.recordIp(player.getUniqueId(), address);
        }
        io.craftrealm.core.CraftRealmCore.rememberName(player.getUniqueId(), player.getName());
        if (punishments.isMuted(player.getUniqueId())) {
            messages.send(player, "punish.still-muted");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        punishments.unload(event.getPlayer().getUniqueId());
        staffTools.clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (staffTools.staffChatEnabled(player.getUniqueId())) {
            event.setCancelled(true);
            String message = PlainTextComponentSerializer.plainText().serialize(event.message());
            Scheduler.sync(() -> staffTools.broadcastStaffChat(player, message));
            return;
        }
        if (punishments.isMuted(player.getUniqueId())) {
            event.setCancelled(true);
            messages.send(player, "punish.muted");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!punishments.isFrozen(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        // Allow head rotation, block actual translation.
        if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ())) {
            event.setTo(from);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!punishments.isFrozen(player.getUniqueId()) || !adminSettings.freezeBlocksCommands()) {
            return;
        }
        String command = event.getMessage().toLowerCase(Locale.ROOT).split(" ")[0];
        if (command.equals("/msg") || command.equals("/tell") || command.equals("/r")
                || command.startsWith("/craftrealm") || command.startsWith("/cr")) {
            return;
        }
        event.setCancelled(true);
        messages.send(player, "punish.frozen-blocked");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && punishments.isFrozen(player.getUniqueId())) {
            event.setCancelled(true);
        }
        if (event.getEntity() instanceof Player victim && punishments.isFrozen(victim.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Human readable expiry used in punishment broadcasts. */
    static String expiry(long expiresAt) {
        return expiresAt <= 0 ? "never" : TimeUtil.formatDate(expiresAt);
    }
}
