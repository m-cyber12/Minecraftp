package io.craftrealm.events.gui;

import io.craftrealm.api.model.Cosmetic;
import io.craftrealm.api.model.CosmeticType;
import io.craftrealm.api.service.CosmeticService;
import io.craftrealm.api.service.ShardService;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.util.Scheduler;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The cosmetic shop.
 *
 * <p>Left click buys an unowned cosmetic, right click equips an owned one. Shard
 * prices are shown in the lore so the player always knows what a click costs
 * before making it.</p>
 */
public final class CosmeticGui extends Gui {

    private final CosmeticService cosmetics;
    private final ShardService shards;
    private final Messages messages;
    private final CosmeticType filter;

    private Set<String> owned = Set.of();

    public CosmeticGui(CosmeticService cosmetics, ShardService shards, Messages messages, GuiTheme theme,
                       Player player, CosmeticType filter) {
        super(player, theme, 6, messages.get("cosmetics.gui.title"));
        this.cosmetics = cosmetics;
        this.shards = shards;
        this.messages = messages;
        this.filter = filter;
    }

    @Override
    public void open() {
        cosmetics.owned(viewer.getUniqueId()).thenAccept(list -> Scheduler.sync(() -> {
            owned = list;
            super.open();
        }));
    }

    @Override
    public void render() {
        List<Cosmetic> catalogue = filter == null ? cosmetics.catalogue() : cosmetics.catalogue(filter);
        int slot = 10;
        for (Cosmetic cosmetic : catalogue) {
            if (slot >= 44) {
                break;
            }
            boolean has = owned.contains(cosmetic.id());
            boolean equipped = cosmetic.id().equals(cosmetics.equippedCached(viewer.getUniqueId(),
                    cosmetic.type()));
            set(slot++, ItemBuilder.of(icon(cosmetic))
                    .name(messages.raw(has ? "cosmetics.gui.entry-owned.name" : "cosmetics.gui.entry.name",
                            "name", cosmetic.displayName()))
                    .lore(lore(cosmetic, has, equipped))
                    .glint(equipped)
                    .build(), click -> {
                if (!has) {
                    cosmetics.purchase(viewer.getUniqueId(), cosmetic.id()).thenAccept(bought ->
                            Scheduler.sync(() -> {
                                messages.send(viewer, bought ? "cosmetics.bought" : "cosmetics.buy-failed",
                                        "name", cosmetic.displayName(),
                                        "price", String.valueOf(cosmetic.shardCost()));
                                theme().play(viewer, bought ? GuiTheme.Click.PURCHASE : GuiTheme.Click.DENY);
                                open();
                            }));
                    return;
                }
                if (equipped) {
                    cosmetics.unequip(viewer.getUniqueId(), cosmetic.type()).thenAccept(done ->
                            Scheduler.sync(() -> {
                                messages.send(viewer, "cosmetics.unequipped", "name", cosmetic.displayName());
                                open();
                            }));
                    return;
                }
                cosmetics.equip(viewer.getUniqueId(), cosmetic.id()).thenAccept(done ->
                        Scheduler.sync(() -> {
                            messages.send(viewer, done ? "cosmetics.equipped" : "cosmetics.equip-failed",
                                    "name", cosmetic.displayName());
                            theme().play(viewer, done ? GuiTheme.Click.SUCCESS : GuiTheme.Click.DENY);
                            open();
                        }));
            });
        }

        set(4, ItemBuilder.of(Material.AMETHYST_SHARD)
                .name(messages.raw("cosmetics.gui.header"))
                .lore(messages.bundle().lines("cosmetics.gui.header-lore",
                        "shards", String.valueOf(shards.cached(viewer.getUniqueId()))))
                .build());

        backButton(() -> viewer.closeInventory());
        closeButton();
        fillEmpty();
    }

    private List<Component> lore(Cosmetic cosmetic, boolean owned, boolean equipped) {
        List<Component> lines = new ArrayList<>(messages.bundle().lines("cosmetics.gui.entry.lore",
                "type", cosmetic.type().name(),
                "value", cosmetic.value(),
                "price", String.valueOf(cosmetic.shardCost()),
                "state", equipped ? "equipped" : owned ? "owned" : "locked"));
        return lines;
    }

    private static Material icon(Cosmetic cosmetic) {
        return switch (cosmetic.type()) {
            case PREFIX -> Material.NAME_TAG;
            case NAME_COLOR -> Material.CYAN_DYE;
            case PARTICLE_TRAIL -> Material.BLAZE_POWDER;
            default -> Material.PAPER;
        };
    }
}
