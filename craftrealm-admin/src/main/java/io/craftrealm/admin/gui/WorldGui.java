package io.craftrealm.admin.gui;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.api.model.HealthSnapshot;
import io.craftrealm.api.model.WorldHealth;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.entity.SpawnCategory;

/**
 * World overview: players, chunks and entities per world, with a weather control.
 *
 * <p>Entity counts are broken down by spawn category because "800 entities" is not
 * actionable, while "800 monsters" and "800 dropped items" need very different
 * responses.</p>
 */
public final class WorldGui extends Gui {

    private final AdminServices services;
    private HealthSnapshot snapshot;

    public WorldGui(AdminServices services, Player player) {
        super(player, services.context.theme, 5, services.context.messages.get("admin.world.title"));
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
        int slot = 10;
        for (WorldHealth world : snapshot.worlds()) {
            if (slot > 25) {
                break;
            }
            World bukkitWorld = org.bukkit.Bukkit.getWorld(world.world());
            set(slot, ItemBuilder.of(bukkitWorld == null ? Material.BARRIER : Material.GRASS_BLOCK)
                    .name(messages.raw("admin.world.entry.name", "world", world.world()))
                    .lore(messages.bundle().lines("admin.world.entry.lore",
                            "players", String.valueOf(world.players()),
                            "chunks", String.valueOf(world.chunks()),
                            "entities", String.valueOf(world.entities()),
                            "time", bukkitWorld == null ? "-" : ticks(bukkitWorld.getTime()),
                            "weather", bukkitWorld == null ? "-" : weather(bukkitWorld)))
                    .build(), click -> {
                if (bukkitWorld == null) {
                    return;
                }
                if (click.rightClick()) {
                    bukkitWorld.setStorm(!bukkitWorld.hasStorm());
                    messages.send(viewer, "admin.world.weather",
                            "world", bukkitWorld.getName(),
                            "weather", weather(bukkitWorld));
                } else if (click.shiftClick()) {
                    bukkitWorld.setTime(bukkitWorld.getTime() < 12_000 ? 18_000 : 1_000);
                    messages.send(viewer, "admin.world.time", "world", bukkitWorld.getName());
                } else {
                    new HealthGui(services, viewer).open();
                }
                theme.play(viewer, GuiTheme.Click.BUTTON);
            });
            slot += 2;
        }

        if (snapshot.worlds().isEmpty()) {
            set(22, ItemBuilder.of(Material.COBWEB)
                    .name(messages.raw("admin.world.empty"))
                    .build());
        }

        set(31, ItemBuilder.of(Material.SPAWNER)
                .name(messages.raw("admin.world.entities.name"))
                .lore(entityBreakdown())
                .build());

        backButton(() -> new ControlRoomGui(services, viewer).open());
        closeButton();
        fillEmpty();
    }

    /** Per-category entity totals across all worlds, biggest first. */
    private java.util.List<net.kyori.adventure.text.Component> entityBreakdown() {
        var messages = services.context.messages;
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            for (SpawnCategory category : SpawnCategory.values()) {
                int amount = world.getEntities().stream()
                        .filter(entity -> !(entity instanceof Player)
                                && entity.getSpawnCategory() == category)
                        .toList().size();
                if (amount > 0) {
                    counts.merge(category.name(), amount, Integer::sum);
                }
            }
        }
        if (counts.isEmpty()) {
            return java.util.List.of(messages.get("admin.world.entities.empty"));
        }
        java.util.List<net.kyori.adventure.text.Component> lines = new java.util.ArrayList<>();
        counts.forEach((name, amount) -> lines.add(messages.get("admin.world.entities.entry",
                "category", name, "amount", String.valueOf(amount))));
        return lines;
    }

    private static String ticks(long time) {
        long hours = (time / 1000 + 6) % 24;
        long minutes = (long) ((time % 1000) / 1000.0 * 60);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", hours, minutes);
    }

    private static String weather(World world) {
        if (world.isThundering()) {
            return "thunder";
        }
        return world.hasStorm() ? "rain" : "clear";
    }
}
