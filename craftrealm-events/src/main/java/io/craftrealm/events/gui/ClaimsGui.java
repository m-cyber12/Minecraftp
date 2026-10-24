package io.craftrealm.events.gui;

import io.craftrealm.api.model.Claim;
import io.craftrealm.api.service.ClaimService;
import io.craftrealm.core.text.Messages;
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
 * "My land" — every claim with its size, upkeep and decay stage.
 *
 * <p>The icon is a fence gate in good standing, cobweb when overdue and a
 * dead bush once build rights are lost, so the state is legible at a glance.</p>
 */
public final class ClaimsGui extends Gui {

    private static final int PER_PAGE = 45;

    private final ClaimService claims;
    private final Messages messages;
    private final int page;
    private List<Claim> owned = List.of();

    public ClaimsGui(ClaimService claims, Messages messages, GuiTheme theme, Player player) {
        this(claims, messages, theme, player, 0);
    }

    public ClaimsGui(ClaimService claims, Messages messages, GuiTheme theme, Player player, int page) {
        super(player, theme, 6, messages.get("claims.gui.title"));
        this.claims = claims;
        this.messages = messages;
        this.page = page;
    }

    @Override
    public void open() {
        claims.claims(viewer.getUniqueId()).thenAccept(list -> Scheduler.sync(() -> {
            owned = list;
            super.open();
        }));
    }

    @Override
    public void render() {
        int pages = Pagination.pages(owned.size(), PER_PAGE);
        int current = Pagination.clamp(page, pages);
        List<Claim> shown = Pagination.slice(owned, current, PER_PAGE);

        for (int index = 0; index < shown.size(); index++) {
            Claim claim = shown.get(index);
            int stage = claims.decayStage(claim);
            set(index, ItemBuilder.of(icon(stage))
                    .name(messages.raw("claims.gui.entry.name", "id", String.valueOf(claim.id())))
                    .lore(lore(claim, stage))
                    .build(), click -> {
                if (!click.shiftClick()) {
                    messages.send(viewer, "admin.player.confirm-shift");
                    return;
                }
                claims.delete(viewer.getUniqueId(), claim.id()).thenAccept(deleted ->
                        Scheduler.sync(() -> {
                            messages.send(viewer, deleted ? "claims.removed" : "claims.not-found");
                            theme().play(viewer, GuiTheme.Click.BUTTON);
                            open();
                        }));
            });
        }

        if (owned.isEmpty()) {
            set(22, ItemBuilder.of(Material.OAK_FENCE)
                    .name(messages.raw("claims.gui.empty"))
                    .build());
        }

        if (current > 0) {
            set(48, ItemBuilder.of(Material.ARROW).name(messages.raw("gui.previous")).build(),
                    click -> new ClaimsGui(claims, messages, theme(), viewer, current - 1).open());
        }
        if (current + 1 < pages) {
            set(50, ItemBuilder.of(Material.SPECTRAL_ARROW).name(messages.raw("gui.next")).build(),
                    click -> new ClaimsGui(claims, messages, theme(), viewer, current + 1).open());
        }

        set(49, ItemBuilder.of(Material.GRASS_BLOCK)
                .name(messages.raw("claims.gui.new.name"))
                .lore(messages.bundle().lines("claims.gui.new.lore"))
                .build(), click -> {
            viewer.closeInventory();
            messages.send(viewer, "claims.gui.new.hint");
        });

        closeButton();
        fillEmpty();
    }

    private List<Component> lore(Claim claim, int stage) {
        var lines = new ArrayList<>(messages.bundle().lines("claims.gui.entry.lore",
                "world", claim.world(),
                "x", String.valueOf(claim.minX() / 16),
                "z", String.valueOf(claim.minZ() / 16),
                "area", String.valueOf(claim.area()),
                "upkeep", String.valueOf(claims.dailyUpkeep(claim)),
                "paid", TimeUtil.formatDate(claim.paidUntil()),
                "stage", stage == 0 ? "ok" : stage == 1 ? "overdue" : "decaying"));
        return lines;
    }

    private static Material icon(int stage) {
        return switch (stage) {
            case 0 -> Material.OAK_FENCE_GATE;
            case 1 -> Material.COBWEB;
            default -> Material.DEAD_BUSH;
        };
    }
}
