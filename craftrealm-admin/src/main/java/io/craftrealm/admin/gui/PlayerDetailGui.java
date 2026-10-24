package io.craftrealm.admin.gui;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.api.model.PlayerView;
import io.craftrealm.api.model.PunishmentType;
import io.craftrealm.api.model.StaffAction;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Everything staff can do to one player, in one menu.
 *
 * <p>Every destructive action asks for a shift-click, which is the cheapest
 * confirmation UI that still works with a mouse only.</p>
 */
public final class PlayerDetailGui extends Gui {

    private final AdminServices services;
    private final UUID target;
    private final String targetName;
    private PlayerView view;
    private long warningCount;
    private int ipCount;

    public PlayerDetailGui(AdminServices services, Player staff, UUID target, String targetName) {
        super(staff, services.context.theme, 6,
                services.context.messages.get("admin.player.title", "player", targetName));
        this.services = services;
        this.target = target;
        this.targetName = targetName;
    }

    @Override
    public void open() {
        CompletableFuture<PlayerView> viewFuture = services.admin.view(target);
        CompletableFuture<Long> warningFuture = services.staffRepository.activeWarningCount(target);
        CompletableFuture<List<String>> ipFuture = services.players.ips(target);
        CompletableFuture.allOf(viewFuture, warningFuture, ipFuture).thenRun(() -> Scheduler.sync(() -> {
            view = viewFuture.join();
            warningCount = warningFuture.join();
            ipCount = ipFuture.join().size();
            super.open();
        }));
    }

    @Override
    public void render() {
        var messages = services.context.messages;
        PlayerView snapshot = view;

        ItemBuilder head = ItemBuilder.of(Material.PLAYER_HEAD)
                .name(messages.raw("admin.player.head.name", "player", targetName))
                .lore(messages.bundle().lines("admin.player.head.lore",
                        "state", snapshot != null && snapshot.online() ? "online" : "offline",
                        "balance", services.money(snapshot == null ? 0 : snapshot.balance()),
                        "shards", String.valueOf(snapshot == null ? 0 : snapshot.shards()),
                        "warnings", String.valueOf(warningCount),
                        "ips", String.valueOf(ipCount),
                        "world", snapshot == null ? "-" : snapshot.world()));
        org.bukkit.inventory.ItemStack stack = head.build();
        if (stack.getItemMeta() instanceof SkullMeta skull) {
            skull.setOwningPlayer(Bukkit.getOfflinePlayer(target));
            stack.setItemMeta(skull);
        }
        set(4, stack);

        // Balance editing: left ±100, shift ±1000.
        set(10, ItemBuilder.of(Material.GOLD_INGOT)
                .name(messages.raw("admin.player.balance.name"))
                .lore(messages.bundle().lines("admin.player.balance.lore"))
                .build(), click -> adjustBalance(click.shiftClick() ? 1000 : 100, click.rightClick() ? -1 : 1));

        set(12, ItemBuilder.of(Material.ENDER_PEARL)
                .name(messages.raw("admin.player.teleport.name"))
                .lore(messages.bundle().lines("admin.player.teleport.lore"))
                .build(), click -> teleport());

        set(14, ItemBuilder.of(services.punishments.isFrozen(target) ? Material.BLUE_ICE : Material.PACKED_ICE)
                .name(messages.raw("admin.player.freeze.name"))
                .lore(messages.bundle().lines("admin.player.freeze.lore"))
                .build(), click -> toggleFreeze());

        set(16, ItemBuilder.of(services.punishments.isMuted(target) ? Material.BELL : Material.NOTE_BLOCK)
                .name(messages.raw("admin.player.mute.name"))
                .lore(messages.bundle().lines("admin.player.mute.lore"))
                .build(), click -> {
            if (click.rightClick()) {
                punish(PunishmentType.MUTE, "lift", 0);
            } else {
                punish(PunishmentType.MUTE, "chat spam", 3_600_000L);
            }
        });

        set(20, ItemBuilder.of(Material.CHEST)
                .name(messages.raw("admin.player.inventory.name"))
                .lore(messages.bundle().lines("admin.player.inventory.lore"))
                .build(), click -> {
            if (click.rightClick()) {
                new InventoryViewGui(services, viewer, target, true).open();
            } else {
                new InventoryViewGui(services, viewer, target, false).open();
            }
        });

        set(22, ItemBuilder.of(Material.BOOK)
                .name(messages.raw("admin.player.notes.name"))
                .lore(messages.bundle().lines("admin.player.notes.lore"))
                .build(), click -> messages.send(viewer, "admin.player.notes.hint",
                "player", targetName));

        set(24, ItemBuilder.of(Material.NAME_TAG)
                .name(messages.raw("admin.player.warn.name"))
                .lore(messages.bundle().lines("admin.player.warn.lore",
                        "count", String.valueOf(warningCount)))
                .build(), click -> {
            if (!click.shiftClick()) {
                messages.send(viewer, "admin.player.warn.confirm");
                return;
            }
            services.punishments.warn(viewer.getUniqueId(), target, "manual warning").thenAccept(escalated ->
                    Scheduler.sync(() -> {
                        messages.send(viewer, "admin.player.warn.done", "player", targetName,
                                "action", escalated == null ? "none" : escalated.name());
                        open();
                    }));
        });

        set(26, ItemBuilder.of(Material.MAP)
                .name(messages.raw("admin.player.ips.name"))
                .lore(messages.bundle().lines("admin.player.ips.lore", "count", String.valueOf(ipCount)))
                .build(), click -> services.players.ips(target).thenAccept(ips -> Scheduler.sync(() -> {
            messages.send(viewer, "admin.player.ips.header", "player", targetName);
            ips.forEach(ip -> messages.sendRaw(viewer, "admin.player.ips.entry", "ip", ip));
        })));

        set(30, ItemBuilder.of(Material.IRON_BOOTS)
                .name(messages.raw("admin.player.kick.name"))
                .lore(messages.bundle().lines("admin.player.kick.lore"))
                .build(), click -> {
            if (!click.shiftClick()) {
                messages.send(viewer, "admin.player.confirm-shift");
                return;
            }
            punish(PunishmentType.KICK, "staff decision", 0);
        });

        set(32, ItemBuilder.of(Material.TNT)
                .name(messages.raw("admin.player.ban.name"))
                .lore(messages.bundle().lines("admin.player.ban.lore"))
                .build(), click -> {
            if (!click.shiftClick()) {
                messages.send(viewer, "admin.player.confirm-shift");
                return;
            }
            punish(click.rightClick() ? PunishmentType.BAN : PunishmentType.TEMPBAN,
                    services.punishments.defaultBanReason(),
                    click.rightClick() ? 0 : 86_400_000L);
        });

        set(40, ItemBuilder.of(Material.CLOCK)
                .name(messages.raw("admin.player.history.name"))
                .lore(messages.bundle().lines("admin.player.history.lore"))
                .build(), click -> services.punishments.history(target).thenAccept(history ->
                Scheduler.sync(() -> {
                    messages.send(viewer, "admin.player.history.header", "player", targetName);
                    history.stream().limit(10).forEach(punishment ->
                            messages.sendRaw(viewer, "admin.player.history.entry",
                                    "type", punishment.type().name(),
                                    "reason", punishment.reason(),
                                    "when", TimeUtil.relative(punishment.createdAt())));
                })));

        backButton(() -> new PlayersGui(services, viewer, 0).open());
        closeButton();
        fillEmpty();
    }

    // ------------------------------------------------------------------ actions

    private void adjustBalance(double delta, int direction) {
        double current = services.context.economy.cachedBalance(target);
        double updated = Math.max(0, current + delta * direction);
        services.context.economy.setBalance(target, updated, viewer.getUniqueId(),
                        "staff balance edit by " + viewer.getName())
                .thenAccept(result -> Scheduler.sync(() -> {
                    if (result.success()) {
                        services.audit.record(viewer.getUniqueId(),
                                direction > 0 ? StaffAction.BALANCE_GIVE : StaffAction.BALANCE_TAKE,
                                target.toString(), "delta " + (delta * direction));
                        messages.send(viewer, "admin.player.balance.done",
                                "amount", services.money(updated), "player", targetName);
                        theme.play(viewer, GuiTheme.Click.PURCHASE);
                    } else {
                        messages.send(viewer, result.messageKey());
                        theme.play(viewer, GuiTheme.Click.DENY);
                    }
                    open();
                }));
    }

    private void teleport() {
        Player online = Bukkit.getPlayer(target);
        if (online == null) {
            messages.send(viewer, "admin.player.offline");
            return;
        }
        viewer.teleport(online.getLocation());
        services.audit.record(viewer.getUniqueId(), StaffAction.TELEPORT, target.toString(), "staff teleport");
        messages.send(viewer, "admin.player.teleported", "player", targetName);
        theme.play(viewer, GuiTheme.Click.CLAIM);
    }

    private void toggleFreeze() {
        boolean targetState = !services.punishments.isFrozen(target);
        services.admin.setFrozen(viewer.getUniqueId(), target, targetState).thenAccept(done ->
                Scheduler.sync(() -> {
                    messages.send(viewer, targetState ? "admin.player.froze" : "admin.player.unfroze",
                            "player", targetName);
                    theme.play(viewer, GuiTheme.Click.BUTTON);
                    open();
                }));
    }

    private void punish(PunishmentType type, String reason, long duration) {
        if (type == PunishmentType.MUTE && duration == 0) {
            services.punishments.lift(viewer.getUniqueId(), target, PunishmentType.MUTE, "manual unmute")
                    .thenAccept(done -> Scheduler.sync(() -> {
                        messages.send(viewer, "admin.player.unmuted", "player", targetName);
                        open();
                    }));
            return;
        }
        services.admin.punish(viewer.getUniqueId(), target, type, reason, duration).thenAccept(punishment ->
                Scheduler.sync(() -> {
                    messages.send(viewer, "admin.player.punished", "player", targetName,
                            "type", type.name());
                    theme.play(viewer, type == PunishmentType.KICK || type == PunishmentType.BAN
                            ? GuiTheme.Click.DENY : GuiTheme.Click.WARNING);
                    open();
                }));
    }
}
