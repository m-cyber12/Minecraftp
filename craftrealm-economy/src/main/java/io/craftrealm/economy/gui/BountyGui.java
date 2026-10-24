package io.craftrealm.economy.gui;

import io.craftrealm.api.model.Bounty;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import io.craftrealm.economy.EconomyContext;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/**
 * The bounty board: every open bounty, richest first.
 *
 * <p>Clicking a bounty shows how much is on that player's head and copies the exact
 * command to add to it.</p>
 */
public final class BountyGui extends Gui {

    private final EconomyContext context;
    private List<Bounty> bounties = List.of();

    public BountyGui(EconomyContext context, Player player) {
        super(player, context.theme, 6, context.messages.get("bounty.title"));
        this.context = context;
    }

    @Override
    public void open() {
        context.bounties().open(45).thenAccept(list -> Scheduler.sync(() -> {
            bounties = list;
            super.open();
        }));
    }

    @Override
    public void render() {
        for (int index = 0; index < bounties.size(); index++) {
            Bounty bounty = bounties.get(index);
            var target = Bukkit.getOfflinePlayer(bounty.target());
            ItemBuilder builder = ItemBuilder.of(Material.PLAYER_HEAD)
                    .name(context.messages.raw("bounty.entry.name",
                            "player", bounty.targetName() == null ? "?" : bounty.targetName(),
                            "amount", context.money(bounty.amount())))
                    .lore(context.messages.bundle().lines("bounty.entry.lore",
                            "amount", context.money(bounty.amount()),
                            "placed", TimeUtil.relative(bounty.createdAt())));
            org.bukkit.inventory.ItemStack stack = builder.build();
            if (stack.getItemMeta() instanceof SkullMeta skull) {
                skull.setOwningPlayer(target);
                stack.setItemMeta(skull);
            }
            set(index, stack, click -> {
                context.messages.send(viewer, "bounty.entry.hint",
                        "player", bounty.targetName(),
                        "command", "/bounty place " + bounty.targetName() + " 500");
                theme.play(viewer, GuiTheme.Click.VILLAGER);
            });
        }

        if (bounties.isEmpty()) {
            set(22, ItemBuilder.of(Material.COBWEB)
                    .name(context.messages.raw("bounty.empty.name"))
                    .lore(context.messages.bundle().lines("bounty.empty.lore"))
                    .build());
        }

        set(49, ItemBuilder.of(GuiTheme.Icon.BOUNTY.material())
                .name(context.messages.raw("bounty.how.name"))
                .lore(context.messages.bundle().lines("bounty.how.lore",
                        "min", context.money(context.settings.bounties().minimum()),
                        "max", context.money(context.settings.bounties().maximum())))
                .build());
        closeButton();
        fillEmpty();
    }
}
