package io.craftrealm.admin.gui;

import io.craftrealm.admin.AdminServices;
import io.craftrealm.api.model.BalanceEntry;
import io.craftrealm.api.model.Transaction;
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
import java.util.concurrent.CompletableFuture;

/**
 * Economy dashboard: money supply, inflation, top earners, suspicious activity.
 *
 * <p>All numbers come from the same sources the economy itself uses — the account
 * table for circulation, the snapshot table for the trend, the ledger for alerts —
 * so what staff see can never disagree with what players spend.</p>
 */
public final class EconomyAdminGui extends Gui {

    private final AdminServices services;

    private double circulation;
    private long accounts;
    private double inflationPercent;
    private List<Double> supplyHistory = List.of();
    private List<BalanceEntry> top = List.of();
    private List<Transaction> alerts = List.of();

    public EconomyAdminGui(AdminServices services, Player player) {
        super(player, services.context.theme, 6, services.context.messages.get("admin.economy.title"));
        this.services = services;
    }

    @Override
    public void open() {
        var economy = services.context.economy;
        double threshold = services.context.settings.economy().alertThreshold();

        CompletableFuture<Double> total = economy.totalInCirculation();
        CompletableFuture<Long> count = services.accounts.accountCount();
        CompletableFuture<List<Double>> history = services.market.supplyHistory(30);
        CompletableFuture<List<BalanceEntry>> richest = economy.top(10);
        CompletableFuture<List<Transaction>> suspicious = economy.suspicious(10, threshold);

        CompletableFuture.allOf(total, count, history, richest, suspicious).thenRun(() ->
                Scheduler.sync(() -> {
                    circulation = total.join();
                    accounts = count.join();
                    supplyHistory = history.join();
                    inflationPercent = inflation(supplyHistory);
                    top = richest.join();
                    alerts = suspicious.join();
                    super.open();
                }));
    }

    @Override
    public void render() {
        var messages = services.context.messages;

        set(4, ItemBuilder.of(Material.GOLD_BLOCK)
                .name(messages.raw("admin.economy.circulation.name"))
                .lore(messages.bundle().lines("admin.economy.circulation.lore",
                        "total", services.money(circulation),
                        "accounts", String.valueOf(accounts)))
                .build());

        set(10, ItemBuilder.of(Material.COMPARATOR)
                .name(messages.raw("admin.economy.inflation.name"))
                .lore(messages.bundle().lines("admin.economy.inflation.lore",
                        "rate", String.format(java.util.Locale.ROOT, "%+.1f%%", inflationPercent),
                        "sparkline", sparkline(supplyHistory)))
                .glint(Math.abs(inflationPercent) > 15.0)
                .build());

        set(12, ItemBuilder.of(Material.PLAYER_HEAD)
                .name(messages.raw("admin.economy.top.name"))
                .lore(topLore())
                .build());

        set(14, ItemBuilder.of(Material.REDSTONE_TORCH)
                .name(messages.raw("admin.economy.alerts.name"))
                .lore(alertLore())
                .glint(!alerts.isEmpty())
                .build(), click -> {
            if (alerts.isEmpty()) {
                return;
            }
            new AlertsGui(services, viewer).open();
        });

        set(16, ItemBuilder.of(Material.HOPPER)
                .name(messages.raw("admin.economy.market.name"))
                .lore(messages.bundle().lines("admin.economy.market.lore"))
                .build(), click -> new MarketAdminGui(services, viewer).open());

        set(22, ItemBuilder.of(Material.WRITABLE_BOOK)
                .name(messages.raw("admin.economy.snapshot.name"))
                .lore(messages.bundle().lines("admin.economy.snapshot.lore"))
                .build(), click -> {
            if (!click.shiftClick()) {
                messages.send(viewer, "admin.player.confirm-shift");
                return;
            }
            services.context.economy.totalInCirculation().thenCompose(total ->
                    services.market.snapshot(total, (int) Math.max(0, accounts))).thenRun(() ->
                    Scheduler.sync(() -> {
                        messages.send(viewer, "admin.economy.snapshot.done");
                        open();
                    }));
        });

        backButton(() -> new ControlRoomGui(services, viewer).open());
        closeButton();
        fillEmpty();
    }

    private List<Component> topLore() {
        var messages = services.context.messages;
        if (top.isEmpty()) {
            return List.of(messages.get("admin.economy.top.empty"));
        }
        List<Component> lines = new ArrayList<>();
        int rank = 1;
        for (BalanceEntry entry : top) {
            lines.add(messages.get("admin.economy.top.entry",
                    "rank", String.valueOf(rank++),
                    "player", entry.name(),
                    "amount", services.money(entry.balance())));
        }
        return lines;
    }

    private List<Component> alertLore() {
        var messages = services.context.messages;
        if (alerts.isEmpty()) {
            return List.of(messages.get("admin.economy.alerts.none"));
        }
        List<Component> lines = new ArrayList<>();
        lines.add(messages.get("admin.economy.alerts.header", "count", String.valueOf(alerts.size())));
        for (Transaction transaction : alerts.stream().limit(6).toList()) {
            lines.add(messages.get("admin.economy.alerts.entry",
                    "type", transaction.type().name(),
                    "amount", services.money(transaction.amount()),
                    "when", TimeUtil.relative(transaction.createdAt())));
        }
        return lines;
    }

    /**
     * Maps circulation samples to block characters. Deliberately coarse: staff only
     * need to see "flat", "climbing" or "spike".
     */
    static String sparkline(List<Double> history) {
        if (history == null || history.isEmpty()) {
            return "";
        }
        String ramp = "\u2581\u2582\u2583\u2584\u2585\u2586\u2587\u2588";
        double min = history.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = history.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double span = Math.max(1e-9, max - min);
        StringBuilder builder = new StringBuilder(history.size());
        for (double value : history) {
            int index = (int) Math.round((value - min) / span * (ramp.length() - 1));
            builder.append(ramp.charAt(Math.max(0, Math.min(ramp.length() - 1, index))));
        }
        return builder.toString();
    }

    /** Percentage change between the oldest and newest snapshot. */
    static double inflation(List<Double> history) {
        if (history == null || history.size() < 2) {
            return 0;
        }
        double first = history.get(0);
        double last = history.get(history.size() - 1);
        if (first <= 0) {
            return 0;
        }
        return (last - first) / first * 100.0;
    }
}
