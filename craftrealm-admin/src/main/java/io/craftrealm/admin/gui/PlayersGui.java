package io.craftrealm.admin.gui;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.api.model.PlayerView;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.ui.Pagination;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

/**
 * Player directory: online first, then recently seen accounts.
 *
 * <p>Each head shows balance, Shards, ping and moderation state at a glance — the
 * information staff need before opening a profile.</p>
 */
public final class PlayersGui extends Gui {

    private static final int PER_PAGE = 45;

    private final AdminServices services;
    private final int page;
    private List<PlayerView> views = List.of();

    public PlayersGui(AdminServices services, Player player, int page) {
        super(player, services.context.theme, 6, services.context.messages.get("admin.players.title"));
        this.services = services;
        this.page = page;
    }

    @Override
    public void open() {
        services.admin.players().thenAccept(list -> Scheduler.sync(() -> {
            list.sort((a, b) -> {
                if (a.online() != b.online()) {
                    return a.online() ? -1 : 1;
                }
                return Long.compare(b.lastSeen(), a.lastSeen());
            });
            views = list;
            super.open();
        }));
    }

    @Override
    public void render() {
        int pages = Pagination.pages(views.size(), PER_PAGE);
        int current = Pagination.clamp(page, pages);
        List<PlayerView> shown = Pagination.slice(views, current, PER_PAGE);

        for (int index = 0; index < shown.size(); index++) {
            PlayerView view = shown.get(index);
            ItemBuilder builder = ItemBuilder.of(Material.PLAYER_HEAD)
                    .name(services.context.messages.raw("admin.players.entry.name", "player", view.name()))
                    .lore(services.context.messages.bundle().lines("admin.players.entry.lore",
                            "state", view.online() ? "online" : "offline",
                            "balance", services.money(view.balance()),
                            "shards", String.valueOf(view.shards()),
                            "ping", String.valueOf(view.ping()),
                            "world", view.world(),
                            "seen", view.online() ? "now" : TimeUtil.relative(view.lastSeen()),
                            "flags", flags(view)));
            org.bukkit.inventory.ItemStack stack = builder.build();
            if (stack.getItemMeta() instanceof SkullMeta skull) {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(view.uuid()));
                stack.setItemMeta(skull);
            }
            set(index, stack, click -> {
                theme.play(viewer, GuiTheme.Click.BUTTON);
                new PlayerDetailGui(services, viewer, view.uuid(), view.name()).open();
            });
        }

        if (views.isEmpty()) {
            set(22, ItemBuilder.of(Material.COBWEB)
                    .name(services.context.messages.raw("admin.players.empty"))
                    .build());
        }

        if (current > 0) {
            set(48, ItemBuilder.of(GuiTheme.Icon.PREVIOUS.material())
                    .name(services.context.messages.raw("gui.previous")).build(),
                    click -> new PlayersGui(services, viewer, current - 1).open());
        }
        if (current + 1 < pages) {
            set(50, ItemBuilder.of(GuiTheme.Icon.NEXT.material())
                    .name(services.context.messages.raw("gui.next")).build(),
                    click -> new PlayersGui(services, viewer, current + 1).open());
        }
        set(49, ItemBuilder.of(Material.PAPER)
                .name(services.context.messages.raw("gui.page",
                        "page", String.valueOf(current + 1), "pages", String.valueOf(pages)))
                .build());
        backButton(() -> new ControlRoomGui(services, viewer).open());
        closeButton();
        fillEmpty();
    }

    private String flags(PlayerView view) {
        if (view.frozen()) {
            return "frozen";
        }
        if (view.muted()) {
            return "muted";
        }
        return "-";
    }
}
