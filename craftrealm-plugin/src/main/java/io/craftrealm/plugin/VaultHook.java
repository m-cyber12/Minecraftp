package io.craftrealm.plugin;

import io.craftrealm.api.model.EconomyResult;
import io.craftrealm.api.model.TransactionType;
import io.craftrealm.core.config.Settings;
import io.craftrealm.core.economy.EconomyServiceImpl;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.ServicePriority;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The Vault bridge.
 *
 * <p><b>Why this class blocks (briefly):</b> Vault's contract is synchronous — a
 * shop plugin calls {@code withdrawPlayer} and expects an answer in the same tick.
 * CraftRealm's own commands and GUIs never take this path; they use the async API.
 * For Vault we therefore perform one indexed ledger write and wait for it, with a
 * two second ceiling. On SQLite (WAL) or a local MySQL that is well under a
 * millisecond, and the alternative — answering "yes" before the write lands — is
 * exactly how economies get duplicated.</p>
 */
public final class VaultHook {

    private static final long TIMEOUT_MILLIS = 2_000L;

    private final CraftRealmPlugin plugin;
    private final Settings settings;
    private final EconomyServiceImpl economy;
    private VaultEconomy service;

    public VaultHook(CraftRealmPlugin plugin, Settings settings, EconomyServiceImpl economy) {
        this.plugin = plugin;
        this.settings = settings;
        this.economy = economy;
    }

    public boolean register() {
        try {
            Class.forName("net.milkbowl.vault.economy.Economy");
        } catch (ClassNotFoundException exception) {
            return false;
        }
        service = new VaultEconomy();
        Bukkit.getServicesManager().register(Economy.class, service, plugin,
                ServicePriority.Highest);
        return true;
    }

    public void unregister() {
        if (service != null) {
            Bukkit.getServicesManager().unregister(Economy.class, service);
            service = null;
        }
    }

    /** The Vault-facing economy implementation. */
    private final class VaultEconomy implements Economy {

        // -------------------------------------------------------------- metadata

        @Override
        public boolean isEnabled() {
            return plugin.isEnabled();
        }

        @Override
        public String getName() {
            return "CraftRealm";
        }

        @Override
        public boolean hasBankSupport() {
            // CraftRealm's bank is player-private, not the shared Vault bank model.
            return false;
        }

        @Override
        public int fractionalDigits() {
            return settings.economy().decimals();
        }

        @Override
        public String format(double amount) {
            return economy.format(amount);
        }

        @Override
        public String currencyNamePlural() {
            return settings.economy().currencyName();
        }

        @Override
        public String currencyNameSingular() {
            return settings.economy().currencyName();
        }

        // -------------------------------------------------------------- accounts

        @Override
        public boolean hasAccount(String playerName) {
            return resolve(playerName) != null;
        }

        @Override
        public boolean hasAccount(OfflinePlayer player) {
            return player != null && player.getUniqueId() != null;
        }

        @Override
        public boolean hasAccount(String playerName, String worldName) {
            return hasAccount(playerName);
        }

        @Override
        public boolean hasAccount(OfflinePlayer player, String worldName) {
            return hasAccount(player);
        }

        @Override
        public boolean createPlayerAccount(String playerName) {
            UUID uuid = resolve(playerName);
            if (uuid == null) {
                return false;
            }
            economy.loadAccount(uuid, playerName);
            return true;
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player) {
            if (player == null) {
                return false;
            }
            economy.loadAccount(player.getUniqueId(), player.getName());
            return true;
        }

        @Override
        public boolean createPlayerAccount(String playerName, String worldName) {
            return createPlayerAccount(playerName);
        }

        @Override
        public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
            return createPlayerAccount(player);
        }

        // --------------------------------------------------------------- balances

        @Override
        public double getBalance(String playerName) {
            UUID uuid = resolve(playerName);
            return uuid == null ? 0 : balanceOf(uuid);
        }

        @Override
        public double getBalance(OfflinePlayer player) {
            return player == null ? 0 : balanceOf(player.getUniqueId());
        }

        @Override
        public double getBalance(String playerName, String world) {
            return getBalance(playerName);
        }

        @Override
        public double getBalance(OfflinePlayer player, String world) {
            return getBalance(player);
        }

        @Override
        public boolean has(String playerName, double amount) {
            return getBalance(playerName) >= amount;
        }

        @Override
        public boolean has(OfflinePlayer player, double amount) {
            return getBalance(player) >= amount;
        }

        @Override
        public boolean has(String playerName, String worldName, double amount) {
            return has(playerName, amount);
        }

        @Override
        public boolean has(OfflinePlayer player, String worldName, double amount) {
            return has(player, amount);
        }

        // ------------------------------------------------------------- mutations

        @Override
        public EconomyResponse withdrawPlayer(String playerName, double amount) {
            UUID uuid = resolve(playerName);
            return uuid == null ? notFound(getBalance(playerName))
                    : withdraw(uuid, amount, "Vault withdrawal: " + playerName);
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
            return player == null ? notFound(0) : withdraw(player.getUniqueId(), amount,
                    "Vault withdrawal: " + player.getName());
        }

        @Override
        public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
            return withdrawPlayer(playerName, amount);
        }

        @Override
        public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
            return withdrawPlayer(player, amount);
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, double amount) {
            UUID uuid = resolve(playerName);
            return uuid == null ? notFound(0)
                    : deposit(uuid, amount, "Vault deposit: " + playerName);
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
            return player == null ? notFound(0) : deposit(player.getUniqueId(), amount,
                    "Vault deposit: " + player.getName());
        }

        @Override
        public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
            return depositPlayer(playerName, amount);
        }

        @Override
        public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
            return depositPlayer(player, amount);
        }

        // ----------------------------------------------------------------- banks

        @Override
        public EconomyResponse createBank(String name, String player) {
            return notImplemented(0);
        }

        @Override
        public EconomyResponse createBank(String name, OfflinePlayer player) {
            return notImplemented(0);
        }

        @Override
        public EconomyResponse deleteBank(String name) {
            return notImplemented(0);
        }

        @Override
        public EconomyResponse bankBalance(String name) {
            return notImplemented(0);
        }

        @Override
        public EconomyResponse bankHas(String name, double amount) {
            return notImplemented(0);
        }

        @Override
        public EconomyResponse bankWithdraw(String name, double amount) {
            return notImplemented(0);
        }

        @Override
        public EconomyResponse bankDeposit(String name, double amount) {
            return notImplemented(0);
        }

        @Override
        public EconomyResponse isBankOwner(String name, String playerName) {
            return notImplemented(0);
        }

        @Override
        public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
            return notImplemented(0);
        }

        @Override
        public EconomyResponse isBankMember(String name, String playerName) {
            return notImplemented(0);
        }

        @Override
        public EconomyResponse isBankMember(String name, OfflinePlayer player) {
            return notImplemented(0);
        }

        @Override
        public List<String> getBanks() {
            return List.of();
        }

        // --------------------------------------------------------------- helpers

        private double balanceOf(UUID uuid) {
            var player = Bukkit.getPlayer(uuid);
            if (player != null) {
                return economy.cachedBalance(uuid);
            }
            return settle(economy.balance(uuid), 0.0);
        }

        private EconomyResponse withdraw(UUID uuid, double amount, String reason) {
            if (!io.craftrealm.core.util.Money.isValidAmount(amount)) {
                return failure(0, "Invalid amount");
            }
            EconomyResult result = settle(economy.withdraw(uuid, amount, TransactionType.SHOP_BUY, reason),
                    null);
            if (result == null) {
                return failure(balanceOf(uuid), "storage timeout");
            }
            return result.success()
                    ? new EconomyResponse(amount, result.balanceAfter(), EconomyResponse.ResponseType.SUCCESS, null)
                    : failure(result.balanceAfter(), errorFor(result.reason()));
        }

        private EconomyResponse deposit(UUID uuid, double amount, String reason) {
            if (!io.craftrealm.core.util.Money.isValidAmount(amount)) {
                return failure(balanceOf(uuid), "Invalid amount");
            }
            EconomyResult result = settle(economy.deposit(uuid, amount, TransactionType.SHOP_SELL, reason),
                    null);
            if (result == null) {
                return failure(balanceOf(uuid), "storage timeout");
            }
            return result.success()
                    ? new EconomyResponse(amount, result.balanceAfter(), EconomyResponse.ResponseType.SUCCESS, null)
                    : failure(result.balanceAfter(), errorFor(result.reason()));
        }

        private <T> T settle(CompletableFuture<T> future, T fallback) {
            try {
                return future.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                plugin.getLogger().warning("A Vault economy call timed out after "
                        + TIMEOUT_MILLIS + "ms — check database latency.");
                return fallback;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return fallback;
            } catch (java.util.concurrent.ExecutionException exception) {
                plugin.getLogger().warning("A Vault economy call failed: "
                        + exception.getCause());
                return fallback;
            }
        }

        private UUID resolve(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            var online = Bukkit.getPlayerExact(name);
            if (online != null) {
                return online.getUniqueId();
            }
            @SuppressWarnings("deprecation")
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            return offline.hasPlayedBefore() || offline.isOnline() ? offline.getUniqueId() : null;
        }

        private EconomyResponse failure(double balance, String message) {
            return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE, message);
        }

        private EconomyResponse notFound(double balance) {
            return new EconomyResponse(0, balance, EconomyResponse.ResponseType.FAILURE,
                    "No CraftRealm account for that player");
        }

        private EconomyResponse notImplemented(double balance) {
            return new EconomyResponse(0, balance, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                    "CraftRealm uses player-private bank accounts, not Vault banks");
        }

        private String errorFor(EconomyResult.Reason reason) {
            return switch (reason) {
                case INSUFFICIENT_FUNDS -> "Insufficient funds";
                case LIMIT_EXCEEDED -> "Amount exceeds the configured limit";
                case RATE_LIMITED -> "Too many transactions, slow down";
                case ACCOUNT_FROZEN -> "Account frozen";
                default -> reason.name();
            };
        }
    }
}
