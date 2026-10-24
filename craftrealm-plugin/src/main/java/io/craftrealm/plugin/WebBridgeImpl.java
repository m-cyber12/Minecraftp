package io.craftrealm.plugin;

import io.craftrealm.api.model.BalanceEntry;
import io.craftrealm.api.model.MarketQuote;
import io.craftrealm.api.model.PunishmentType;
import io.craftrealm.api.model.Report;
import io.craftrealm.api.model.ReportStatus;
import io.craftrealm.api.model.Transaction;
import io.craftrealm.core.CraftRealmCore;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.web.WebBridge;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Connects the dashboard to the running server.
 *
 * <p>HTTP calls arrive on Jetty threads, so this class does three things carefully:
 * reads caches where possible, uses the async services for everything else, and hops
 * to the main thread for commands and punishments (Bukkit requires it).</p>
 */
public final class WebBridgeImpl implements WebBridge {

    /** Recorded as the actor for punishments applied from the dashboard. */
    private static final UUID WEB_ACTOR = new UUID(0L, 0L);

    private final CraftRealmPlugin plugin;
    private final ConsoleBuffer console;

    public WebBridgeImpl(CraftRealmPlugin plugin) {
        this.plugin = plugin;
        this.console = ConsoleBuffer.attach(plugin.settings().web().maxConsoleLines());
    }

    public void close() {
        ConsoleBuffer.detach(console);
    }

    @Override
    public ServerStatus status() {
        double[] tps = Bukkit.getServer().getTPS();
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long max = runtime.maxMemory() / (1024L * 1024L);
        return new ServerStatus(Bukkit.getServer().getName(), Bukkit.getVersion(),
                Bukkit.getOnlinePlayers().size(), Bukkit.getMaxPlayers(),
                tps.length > 0 ? Math.min(20.0, tps[0]) : 20.0, used, max,
                java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime(),
                Bukkit.getName());
    }

    @Override
    public List<PlayerRow> players() {
        List<PlayerRow> rows = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID uuid = player.getUniqueId();
            rows.add(new PlayerRow(uuid, player.getName(), true,
                    plugin.economyService().cachedBalance(uuid),
                    plugin.shardService().cached(uuid), player.getPing(),
                    player.getWorld().getName(), player.getGameMode().name()));
        }
        return rows;
    }

    @Override
    public EconomySnapshot economy() {
        // Joining here is safe: these are indexed aggregate queries and the dashboard
        // already polls off the tick thread. Keep the timeout behaviour predictable by
        // answering with whatever the futures produced.
        double circulation = join(plugin.economyService().totalInCirculation(), 0.0);
        long accounts = join(plugin.database() == null ? null
                : new io.craftrealm.core.repo.AccountRepository(plugin.database()).accountCount(), 0L);
        List<Double> history = join(new io.craftrealm.core.repo.MarketRepository(plugin.database())
                .supplyHistory(30), List.of());

        List<BalanceRow> top = new ArrayList<>();
        for (BalanceEntry entry : join(plugin.economyService().top(10), List.of())) {
            top.add(new BalanceRow(entry.name(), entry.balance()));
        }
        List<AlertRow> alerts = new ArrayList<>();
        for (Transaction transaction : join(plugin.economyService().suspicious(20,
                plugin.settings().economy().alertThreshold()), List.of())) {
            alerts.add(new AlertRow(transaction.id(), transaction.type().name(), transaction.amount(),
                    transaction.reason(), transaction.createdAt()));
        }
        return new EconomySnapshot(circulation, accounts, inflation(history), history, top, alerts);
    }

    @Override
    public List<AuditRow> audit(int limit) {
        List<AuditRow> rows = new ArrayList<>();
        for (var entry : join(new io.craftrealm.core.repo.StaffRepository(plugin.database()).audit(limit),
                List.of())) {
            rows.add(new AuditRow(entry.staffName(), entry.action(), entry.target(), entry.detail(),
                    entry.createdAt()));
        }
        return rows;
    }

    @Override
    public List<ReportRow> reports() {
        List<ReportRow> rows = new ArrayList<>();
        for (Report report : join(new io.craftrealm.core.repo.StaffRepository(plugin.database())
                .reports(ReportStatus.OPEN, 50), List.of())) {
            rows.add(new ReportRow(report.id(), report.reporterName(), report.targetName(),
                    report.reason(), report.status().name(), report.createdAt()));
        }
        return rows;
    }

    @Override
    public List<MarketRow> market(int limit) {
        List<MarketRow> rows = new ArrayList<>();
        for (MarketQuote quote : join(plugin.marketService().allQuotes(), List.of())) {
            if (rows.size() >= limit) {
                break;
            }
            rows.add(new MarketRow(quote.material() == null ? "?" : quote.material().name(),
                    quote.price(), quote.basePrice(), quote.totalSold(), quote.totalBought()));
        }
        return rows;
    }

    @Override
    public List<String> console(int lines) {
        return console.tail(lines);
    }

    @Override
    public boolean runCommand(String command) {
        if (!plugin.settings().web().allowConsole()) {
            return false;
        }
        Scheduler.sync(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command));
        return true;
    }

    @Override
    public boolean broadcast(String message) {
        Scheduler.sync(() -> Bukkit.broadcast(plugin.messages().prefixed("admin.broadcast",
                "message", message)));
        return true;
    }

    @Override
    public boolean punish(String targetName, String type, String reason, long durationMillis) {
        UUID target = findPlayer(targetName);
        if (target == null) {
            return false;
        }
        PunishmentType punishmentType;
        try {
            punishmentType = PunishmentType.valueOf(type.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return false;
        }
        plugin.adminModule().punishments().punish(WEB_ACTOR, target, punishmentType, reason, durationMillis);
        return true;
    }

    @Override
    public UUID findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        @SuppressWarnings("deprecation")
        var offline = Bukkit.getOfflinePlayer(name);
        return offline.hasPlayedBefore() ? offline.getUniqueId() : null;
    }

    // ----------------------------------------------------------------- helpers

    private static <T> T join(java.util.concurrent.CompletableFuture<T> future, T fallback) {
        if (future == null) {
            return fallback;
        }
        try {
            return future.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception exception) {
            CraftRealmCore.logger().warning("Dashboard read failed: " + exception.getMessage());
            return fallback;
        }
    }

    private static double inflation(List<Double> history) {
        if (history == null || history.size() < 2) {
            return 0;
        }
        double first = history.get(0);
        double last = history.get(history.size() - 1);
        return first <= 0 ? 0 : (last - first) / first * 100.0;
    }
}
