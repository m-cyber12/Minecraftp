package io.craftrealm.web;

import java.util.List;
import java.util.UUID;

/**
 * The only contact surface between the dashboard and the server.
 *
 * <p>Implemented by the plugin module with Bukkit calls. The web module itself has
 * no Bukkit dependency, so it can be unit tested and even run outside a server.
 * Every method must be safe to call from an HTTP thread: implementations are
 * expected to read caches or hop to the main thread themselves.</p>
 */
public interface WebBridge {

    // ------------------------------------------------------------------ status

    ServerStatus status();

    // ----------------------------------------------------------------- players

    List<PlayerRow> players();

    // ----------------------------------------------------------------- economy

    EconomySnapshot economy();

    // ------------------------------------------------------------------- audit

    List<AuditRow> audit(int limit);

    // ----------------------------------------------------------------- reports

    List<ReportRow> reports();

    // ------------------------------------------------------------------ market

    List<MarketRow> market(int limit);

    // ----------------------------------------------------------------- console

    List<String> console(int lines);

    /** Runs a console command; {@code false} when the dashboard may not execute. */
    boolean runCommand(String command);

    /** Broadcasts a message to the server. */
    boolean broadcast(String message);

    /** Applies a punishment from the dashboard. */
    boolean punish(String targetName, String type, String reason, long durationMillis);

    /** Attempts to find a player UUID by name (online or cached). */
    UUID findPlayer(String name);

    record ServerStatus(String name, String version, int online, int maxPlayers, double tps,
                        long usedRamMb, long maxRamMb, long uptimeMs, String software) {
    }

    record PlayerRow(UUID uuid, String name, boolean online, double balance, long shards,
                     int ping, String world, String gameMode) {
    }

    record EconomySnapshot(double circulation, long accounts, double inflationPercent,
                           List<Double> history, List<BalanceRow> top,
                           List<AlertRow> alerts) {
    }

    record BalanceRow(String name, double balance) {
    }

    record AlertRow(long id, String type, double amount, String reason, long createdAt) {
    }

    record AuditRow(String staff, String action, String target, String detail, long createdAt) {
    }

    record ReportRow(long id, String reporter, String target, String reason, String status,
                     long createdAt) {
    }

    record MarketRow(String material, double price, double basePrice, long sold, long bought) {
    }
}
