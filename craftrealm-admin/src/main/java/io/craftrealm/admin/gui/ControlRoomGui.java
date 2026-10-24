package io.craftrealm.admin.gui;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * The Control Room: the entrance to every staff tool.
 *
 * <p>Six categories, each an icon that says what it does — villagers for people,
 * gold for money, TNT for punishments, a compass for the world, a redstone lamp for
 * health and a book for logs. A live health strip runs along the bottom so staff
 * can spot trouble before opening anything.</p>
 */
public final class ControlRoomGui extends Gui {

    private final AdminServices services;

    public ControlRoomGui(AdminServices services, Player player) {
        super(player, services.context.theme, 5, services.context.messages.get("admin.room.title"));
        this.services = services;
    }

    @Override
    public void render() {
        var health = services.health.snapshot();
        var context = services.context;

        set(10, category(GuiTheme.Icon.PLAYERS.material(), "players",
                        List.of(context.messages.get("admin.room.players-count",
                                "count", String.valueOf(health.onlinePlayers()))))
                , click -> new PlayersGui(services, viewer, 0).open());

        set(12, category(GuiTheme.Icon.ECONOMY.material(), "economy",
                        List.of(context.messages.get("admin.room.economy-count",
                                "amount", context.money(context.economy.cachedBalance(viewer.getUniqueId())))))
                , click -> new EconomyAdminGui(services, viewer).open());

        set(14, category(GuiTheme.Icon.PUNISHMENTS.material(), "punishments",
                        List.of(context.messages.get("admin.room.punishments-hint")))
                , click -> new PunishmentsGui(services, viewer).open());

        set(16, category(GuiTheme.Icon.WORLD.material(), "world",
                        List.of(context.messages.get("admin.room.world-count",
                                "count", String.valueOf(health.worlds().size()))))
                , click -> new WorldGui(services, viewer).open());

        set(28, category(GuiTheme.Icon.HEALTH.material(), "health",
                        List.of(context.messages.get("admin.room.health-tps",
                                        "tps", io.craftrealm.admin.health.HealthMonitor.formatTps(health.currentTps())),
                                context.messages.get("admin.room.health-ram",
                                        "used", String.valueOf(health.usedRamMb()),
                                        "max", String.valueOf(health.maxRamMb()))))
                , click -> new HealthGui(services, viewer).open());

        set(30, category(GuiTheme.Icon.LOGS.material(), "logs",
                        List.of(context.messages.get("admin.room.logs-hint")))
                , click -> new LogsGui(services, viewer, 0).open());

        // Live health strip.
        set(31, ItemBuilder.of(Material.REDSTONE_LAMP)
                .name(context.messages.raw("admin.room.strip.name"))
                .lore(context.messages.bundle().lines("admin.room.strip.lore",
                        "tps", io.craftrealm.admin.health.HealthMonitor.formatTps(health.currentTps()),
                        "ram", String.format(java.util.Locale.ROOT, "%.0f%%", health.ramPercent() * 100),
                        "uptime", TimeUtil.formatDuration(health.uptimeMs()),
                        "players", String.valueOf(health.onlinePlayers())))
                .glint(health.currentTps() < 18.0)
                .build());
        closeButton();
        fillEmpty();
    }

    private org.bukkit.inventory.ItemStack category(Material material, String key, List<Component> extraLore) {
        var messages = services.context.messages;
        List<Component> lore = new java.util.ArrayList<>(messages.bundle().lines("admin.room." + key + ".lore"));
        lore.addAll(extraLore);
        return ItemBuilder.of(material)
                .name(messages.raw("admin.room." + key + ".name"))
                .lore(lore)
                .build();
    }
}
