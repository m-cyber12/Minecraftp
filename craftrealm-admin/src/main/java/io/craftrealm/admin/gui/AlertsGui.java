package io.craftrealm.admin.gui;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.api.model.StaffAction;
import io.craftrealm.api.model.Transaction;
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
 * Suspicious transactions with a one-click rollback.
 *
 * <p>A rollback writes a compensating ledger row; it never deletes history. Shift
 * click is required because reversing money is the single most dangerous button in
 * the whole panel.</p>
 */
public final class AlertsGui extends Gui {

    private static final int PER_PAGE = 45;

    private final AdminServices services;
    private final int page;
    private List<Transaction> alerts = List.of();

    public AlertsGui(AdminServices services, Player player) {
        this(services, player, 0);
    }

    public AlertsGui(AdminServices services, Player player, int page) {
        super(player, services.context.theme, 6, services.context.messages.get("admin.alerts.title"));
        this.services = services;
        this.page = page;
    }

    @Override
    public void open() {
        services.context.economy.suspicious(200, services.context.settings.economy().alertThreshold())
                .thenAccept(list -> Scheduler.sync(() -> {
                    alerts = list;
                    super.open();
                }));
    }

    @Override
    public void render() {
        var messages = services.context.messages;
        int pages = Pagination.pages(alerts.size(), PER_PAGE);
        int current = Pagination.clamp(page, pages);
        List<Transaction> shown = Pagination.slice(alerts, current, PER_PAGE);

        for (int index = 0; index < shown.size(); index++) {
            Transaction transaction = shown.get(index);
            set(index, ItemBuilder.of(transaction.reverted() ? Material.GRAY_DYE : Material.REDSTONE)
                    .name(messages.raw("admin.alerts.entry.name",
                            "amount", services.money(transaction.amount()),
                            "type", transaction.type().name()))
                    .lore(lore(transaction))
                    .glint(!transaction.reverted())
                    .build(), click -> {
                if (transaction.reverted()) {
                    messages.send(viewer, "admin.alerts.already");
                    return;
                }
                if (!click.shiftClick()) {
                    messages.send(viewer, "admin.player.confirm-shift");
                    return;
                }
                services.context.economy.rollback(transaction.id(), viewer.getUniqueId())
                        .thenAccept(reversed -> Scheduler.sync(() -> {
                            if (reversed > 0) {
                                services.audit.record(viewer.getUniqueId(), StaffAction.ROLLBACK,
                                        String.valueOf(transaction.id()), "reversed " + reversed);
                                messages.send(viewer, "admin.alerts.rolled-back",
                                        "amount", services.money(reversed));
                                theme.play(viewer, GuiTheme.Click.SUCCESS);
                            } else {
                                messages.send(viewer, "admin.alerts.failed");
                                theme.play(viewer, GuiTheme.Click.DENY);
                            }
                            open();
                        }));
            });
        }

        if (current > 0) {
            set(48, ItemBuilder.of(GuiTheme.Icon.PREVIOUS.material())
                    .name(messages.raw("gui.previous")).build(),
                    click -> new AlertsGui(services, viewer, current - 1).open());
        }
        if (current + 1 < pages) {
            set(50, ItemBuilder.of(GuiTheme.Icon.NEXT.material())
                    .name(messages.raw("gui.next")).build(),
                    click -> new AlertsGui(services, viewer, current + 1).open());
        }
        backButton(() -> new EconomyAdminGui(services, viewer).open());
        closeButton();
        fillEmpty();
    }

    private List<Component> lore(Transaction transaction) {
        var messages = services.context.messages;
        List<Component> lines = new ArrayList<>(messages.bundle().lines("admin.alerts.entry.lore",
                "reason", transaction.reason(),
                "id", String.valueOf(transaction.id()),
                "when", TimeUtil.relative(transaction.createdAt()),
                "server", transaction.server(),
                "state", transaction.reverted() ? "reverted" : "live"));
        if (transaction.operationId() != null) {
            lines.add(messages.get("admin.alerts.entry.operation", "op", transaction.operationId()));
        }
        return lines;
    }
}
