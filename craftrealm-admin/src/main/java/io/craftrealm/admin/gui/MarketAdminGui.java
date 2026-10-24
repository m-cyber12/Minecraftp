package io.craftrealm.admin.gui;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.api.model.MarketQuote;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.ui.Pagination;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Market overview: current price against the anchor price, with a trend arrow.
 *
 * <p>Colours follow the theme's meaning: emerald for "selling high", lava for
 * "crashed", parchment for steady.</p>
 */
public final class MarketAdminGui extends Gui {

    private static final int PER_PAGE = 45;

    private final AdminServices services;
    private final int page;
    private List<MarketQuote> quotes = List.of();

    public MarketAdminGui(AdminServices services, Player player) {
        this(services, player, 0);
    }

    public MarketAdminGui(AdminServices services, Player player, int page) {
        super(player, services.context.theme, 6, services.context.messages.get("admin.market.title"));
        this.services = services;
        this.page = page;
    }

    @Override
    public void open() {
        services.context.market.allQuotes().thenAccept(list -> Scheduler.sync(() -> {
            quotes = list;
            super.open();
        }));
    }

    @Override
    public void render() {
        var messages = services.context.messages;
        int pages = Pagination.pages(quotes.size(), PER_PAGE);
        int current = Pagination.clamp(page, pages);
        List<MarketQuote> shown = Pagination.slice(quotes, current, PER_PAGE);

        for (int index = 0; index < shown.size(); index++) {
            MarketQuote quote = shown.get(index);
            Material material = quote.material();
            set(index, ItemBuilder.of(material == null ? Material.BARRIER : material)
                    .name(messages.raw("admin.market.entry.name", "item", pretty(material)))
                    .lore(lore(quote))
                    .build());
        }

        if (current > 0) {
            set(48, ItemBuilder.of(GuiTheme.Icon.PREVIOUS.material())
                    .name(messages.raw("gui.previous")).build(),
                    click -> new MarketAdminGui(services, viewer, current - 1).open());
        }
        if (current + 1 < pages) {
            set(50, ItemBuilder.of(GuiTheme.Icon.NEXT.material())
                    .name(messages.raw("gui.next")).build(),
                    click -> new MarketAdminGui(services, viewer, current + 1).open());
        }
        set(45, ItemBuilder.of(Material.CLOCK)
                .name(messages.raw("admin.market.refresh"))
                .build(), click -> open());
        backButton(() -> new EconomyAdminGui(services, viewer).open());
        closeButton();
        fillEmpty();
    }

    private List<Component> lore(MarketQuote quote) {
        var messages = services.context.messages;
        List<Component> lines = new ArrayList<>(messages.bundle().lines("admin.market.entry.lore",
                "price", services.money(quote.price()),
                "base", services.money(quote.basePrice()),
                "trend", arrow(quote),
                "sold", String.valueOf(quote.totalSold()),
                "bought", String.valueOf(quote.totalBought()),
                "pressure", String.format(java.util.Locale.ROOT, "%+.2f", quote.pressure()),
                "updated", TimeUtil.relative(quote.updatedAt())));
        return lines;
    }

    private static String arrow(MarketQuote quote) {
        double delta = quote.basePrice() <= 0 ? 0 : (quote.price() - quote.basePrice()) / quote.basePrice();
        if (delta > 0.10) {
            return "\u25B2";
        }
        if (delta < -0.10) {
            return "\u25BC";
        }
        return "\u25AC";
    }

    private static String pretty(Material material) {
        if (material == null) {
            return "?";
        }
        String name = material.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
