package io.craftrealm.admin.gui;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.admin.health.HealthMonitor;
import io.craftrealm.api.model.ChunkHotspot;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Server health: TPS bars, heap, uptime and the worst chunks.
 *
 * <p>The cleanup button only ever touches chunks the lag finder flagged, and it
 * leaves named entities alone unless mobs are explicitly included.</p>
 */
public final class HealthGui extends Gui {

    private final AdminServices services;
    private io.craftrealm.api.model.HealthSnapshot snapshot;

    public HealthGui(AdminServices services, Player player) {
        super(player, services.context.theme, 6, services.context.messages.get("admin.health.title"));
        this.services = services;
    }

    @Override
    public void open() {
        snapshot = services.health.snapshot();
        super.open();
    }

    @Override
    public void render() {
        var messages = services.context.messages;
        var health = snapshot;

        set(4, ItemBuilder.of(Material.CLOCK)
                .name(messages.raw("admin.health.tps.name"))
                .lore(messages.bundle().lines("admin.health.tps.lore",
                        "now", HealthMonitor.formatTps(health.currentTps()),
                        "one", HealthMonitor.bar(health.currentTps() / 20.0, 20),
                        "five", HealthMonitor.formatTps(health.tps()[Math.min(1, health.tps().length - 1)]),
                        "fifteen", HealthMonitor.formatTps(health.tps()[Math.min(2, health.tps().length - 1)])))
                .glint(health.currentTps() < 18.0)
                .build());

        set(11, ItemBuilder.of(Material.REDSTONE_BLOCK)
                .name(messages.raw("admin.health.ram.name"))
                .lore(messages.bundle().lines("admin.health.ram.lore",
                        "used", String.valueOf(health.usedRamMb()),
                        "max", String.valueOf(health.maxRamMb()),
                        "bar", HealthMonitor.bar(health.ramPercent(), 20),
                        "percent", String.format(java.util.Locale.ROOT, "%.0f%%", health.ramPercent() * 100)))
                .glint(health.ramPercent() > 0.85)
                .build());

        set(13, ItemBuilder.of(Material.EMERALD)
                .name(messages.raw("admin.health.uptime.name"))
                .lore(messages.bundle().lines("admin.health.uptime.lore",
                        "uptime", TimeUtil.formatDuration(health.uptimeMs()),
                        "players", String.valueOf(health.onlinePlayers()),
                        "worlds", String.valueOf(health.worlds().size())))
                .build());

        set(15, ItemBuilder.of(Material.HOPPER)
                .name(messages.raw("admin.health.cleanup.name"))
                .lore(messages.bundle().lines("admin.health.cleanup.lore",
                        "threshold", String.valueOf(services.lagFinder.entityThreshold())))
                .build(), click -> {
            if (!click.shiftClick()) {
                messages.send(viewer, "admin.player.confirm-shift");
                return;
            }
            cleanup(health.hotspots(), click.rightClick());
        });

        int slot = 27;
        for (ChunkHotspot hotspot : health.hotspots()) {
            if (slot >= 44) {
                break;
            }
            set(slot++, ItemBuilder.of(Material.COBWEB)
                    .name(messages.raw("admin.health.hotspot.name",
                            "world", hotspot.world(),
                            "x", String.valueOf(hotspot.chunkX() * 16),
                            "z", String.valueOf(hotspot.chunkZ() * 16)))
                    .lore(messages.bundle().lines("admin.health.hotspot.lore",
                            "entities", String.valueOf(hotspot.entities()),
                            "type", hotspot.topType()))
                    .build(), click -> {
                if (!click.shiftClick()) {
                    return;
                }
                int removed = services.lagFinder.cleanup(hotspot, false);
                messages.send(viewer, "admin.health.cleanup.done", "count", String.valueOf(removed));
                theme.play(viewer, GuiTheme.Click.SUCCESS);
                open();
            });
        }

        backButton(() -> new ControlRoomGui(services, viewer).open());
        closeButton();
        fillEmpty();
    }

    private void cleanup(List<ChunkHotspot> hotspots, boolean includeMobs) {
        var messages = services.context.messages;
        int removed = 0;
        int chunks = 0;
        for (ChunkHotspot hotspot : hotspots) {
            if (chunks++ >= services.lagFinder.maxChunksToClean()) {
                break;
            }
            removed += services.lagFinder.cleanup(hotspot, includeMobs);
        }
        messages.send(viewer, "admin.health.cleanup.done", "count", String.valueOf(removed));
        services.audit.record(viewer.getUniqueId(), "LAG_CLEANUP", "server",
                "chunks=" + chunks + " entities=" + removed + " mobs=" + includeMobs);
        theme.play(viewer, GuiTheme.Click.SUCCESS);
        open();
    }

    /** Sparkline of TPS history would need sampling; the bar already tells the story. */
    static List<Component> placeholder() {
        return new ArrayList<>();
    }
}
