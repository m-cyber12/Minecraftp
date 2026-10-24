package io.craftrealm.economy.command;

import io.craftrealm.core.command.BaseCommand;
import io.craftrealm.core.text.Messages;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.economy.EconomyContext;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** {@code /bal} — wallet, savings and Shards at a glance. */
public final class BalanceCommand extends BaseCommand {

    private final EconomyContext context;

    public BalanceCommand(Messages messages, EconomyContext context) {
        super(messages);
        this.context = context;
    }

    @Override
    public String name() {
        return "balance";
    }

    @Override
    public List<String> aliases() {
        return List.of("bal", "money");
    }

    @Override
    public String permission() {
        return "craftrealm.economy.balance";
    }

    @Override
    public String usage() {
        return "/bal [player]";
    }

    @Override
    public String description() {
        return "Show your wallet, savings and Shards";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        UUID account;
        String displayName;
        if (args.length > 0) {
            if (!sender.hasPermission("craftrealm.economy.balance.others")) {
                messages.send(sender, "command.no-permission");
                return;
            }
            UUID resolved = uuidOf(args[0]);
            if (resolved == null) {
                messages.send(sender, "command.unknown-player", "player", args[0]);
                return;
            }
            account = resolved;
            Player online = Bukkit.getPlayer(resolved);
            displayName = online != null ? online.getName() : args[0];
        } else {
            Player player = requirePlayer(sender);
            if (player == null) {
                return;
            }
            account = player.getUniqueId();
            displayName = player.getName();
        }

        final UUID uuid = account;
        final String name = displayName;
        CompletableFuture<Double> wallet = context.economy.balance(uuid);
        CompletableFuture<Long> shards = context.shards.balance(uuid);
        CompletableFuture<Double> vault = context.bank.account(uuid).thenApply(a -> a.balance());
        CompletableFuture.allOf(wallet, shards, vault).thenRun(() -> {
            double walletValue = wallet.join();
            long shardValue = shards.join();
            double vaultValue = vault.join();
            Scheduler.sync(() -> {
                messages.send(sender, "economy.balance.header", "player", name);
                messages.sendRaw(sender, "economy.balance.wallet", "amount", context.money(walletValue));
                messages.sendRaw(sender, "economy.balance.bank", "amount", context.money(vaultValue));
                messages.sendRaw(sender, "economy.balance.shards", "amount", String.valueOf(shardValue));
            });
        }).exceptionally(throwable -> {
            Scheduler.sync(() -> messages.send(sender, "command.error",
                    "error", String.valueOf(throwable.getMessage())));
            return null;
        });
    }

    @Override
    public List<String> complete(CommandSender sender, String[] args) {
        return args.length == 1 ? onlinePlayers(args[0]) : List.of();
    }
}
