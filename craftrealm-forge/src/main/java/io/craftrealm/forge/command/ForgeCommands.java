package io.craftrealm.forge.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.craftrealm.forge.api.ForgeBalanceChangeEvent;
import io.craftrealm.forge.api.ForgeTransactionType;
import io.craftrealm.forge.claims.ForgeClaims;
import io.craftrealm.forge.config.ForgeConfig;
import io.craftrealm.forge.cosmetics.ForgeCosmetics;
import io.craftrealm.forge.discord.ForgeDiscordBridge;
import io.craftrealm.forge.events.ForgeEventService;
import io.craftrealm.forge.market.ForgeMarket;
import io.craftrealm.forge.storage.ForgeDatabase;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** Native Brigadier commands for Forge's first economy slice. */
public final class ForgeCommands {

    private ForgeCommands() {
    }

    public static void register(RegisterCommandsEvent event, Supplier<ForgeDatabase> database,
                                Supplier<ForgeClaims> claims, Supplier<ForgeMarket> market,
                                Supplier<ForgeEventService> events, Supplier<ForgeCosmetics> cosmetics,
                                Supplier<ForgeDiscordBridge> discord, Logger logger) {
        var dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> balance = Commands.literal("balance")
                .executes(context -> balanceSelf(context.getSource(), database, logger))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> balanceOther(context.getSource(),
                                EntityArgument.getPlayer(context, "player"), database, logger)));
        dispatcher.register(balance);
        dispatcher.register(Commands.literal("bal").redirect(balance.build()));

        dispatcher.register(Commands.literal("pay")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(context -> pay(context.getSource(),
                                        EntityArgument.getPlayer(context, "player"),
                                        DoubleArgumentType.getDouble(context, "amount"), database, logger)))));
        dispatcher.register(Commands.literal("baltop")
                .executes(context -> baltop(context.getSource(), database, logger)));
        dispatcher.register(Commands.literal("bank")
                .executes(context -> bankBalance(context.getSource(), database, logger))
                .then(Commands.literal("balance")
                        .executes(context -> bankBalance(context.getSource(), database, logger)))
                .then(Commands.literal("deposit")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(context -> bankMove(context.getSource(),
                                        DoubleArgumentType.getDouble(context, "amount"), true, database, logger))))
                .then(Commands.literal("withdraw")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(context -> bankMove(context.getSource(),
                                        DoubleArgumentType.getDouble(context, "amount"), false, database, logger))))
                .then(Commands.literal("loan")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(context -> takeLoan(context.getSource(),
                                        DoubleArgumentType.getDouble(context, "amount"), database, logger))))
                .then(Commands.literal("repay")
                        .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                .executes(context -> repayLoan(context.getSource(),
                                        DoubleArgumentType.getDouble(context, "amount"), database, logger)))));
        dispatcher.register(Commands.literal("claim")
                .executes(context -> claim(context.getSource(), claims, logger)));
        dispatcher.register(Commands.literal("unclaim")
                .executes(context -> unclaim(context.getSource(), claims)));
        dispatcher.register(Commands.literal("claims")
                .executes(context -> listClaims(context.getSource(), claims)));
        dispatcher.register(Commands.literal("season")
                .executes(context -> seasonStatus(context.getSource(), database, logger))
                .then(Commands.literal("top")
                        .executes(context -> seasonTop(context.getSource(), database, logger))));
        dispatcher.register(Commands.literal("cosmetic")
                .executes(context -> cosmeticStatus(context.getSource(), cosmetics))
                .then(Commands.literal("equip")
                        .then(Commands.argument("trail", StringArgumentType.word())
                                .executes(context -> equipCosmetic(context.getSource(),
                                        StringArgumentType.getString(context, "trail"), cosmetics, logger))))
                .then(Commands.literal("clear")
                        .executes(context -> equipCosmetic(context.getSource(), "NONE", cosmetics, logger))));
        dispatcher.register(Commands.literal("quests")
                .executes(context -> questStatus(context.getSource(), database, logger))
                .then(Commands.literal("claim")
                        .executes(context -> claimQuest(context.getSource(), database, logger))));
        dispatcher.register(Commands.literal("jobs")
                .executes(context -> jobStatus(context.getSource(), database, logger))
                .then(Commands.literal("choose")
                        .then(Commands.argument("job", StringArgumentType.word())
                                .executes(context -> chooseJob(context.getSource(),
                                        StringArgumentType.getString(context, "job"), database, logger)))));
        dispatcher.register(Commands.literal("market")
                .executes(context -> marketList(context.getSource(), market, logger))
                .then(Commands.literal("quote")
                        .then(Commands.argument("item", StringArgumentType.word())
                                .executes(context -> marketQuote(context.getSource(),
                                        StringArgumentType.getString(context, "item"), market, logger)))));
        dispatcher.register(Commands.literal("shop")
                .then(Commands.literal("buy")
                        .then(Commands.argument("item", StringArgumentType.word())
                                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64))
                                        .executes(context -> buyItem(context.getSource(),
                                                StringArgumentType.getString(context, "item"),
                                                IntegerArgumentType.getInteger(context, "quantity"),
                                                market, logger))))));
        dispatcher.register(Commands.literal("sell")
                .then(Commands.argument("item", StringArgumentType.word())
                        .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 64))
                                .executes(context -> sellItem(context.getSource(),
                                        StringArgumentType.getString(context, "item"),
                                        IntegerArgumentType.getInteger(context, "quantity"),
                                        market, logger)))));
        dispatcher.register(Commands.literal("cradmin")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("give")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                        .executes(context -> adminAdjust(context.getSource(),
                                                EntityArgument.getPlayer(context, "player"),
                                                DoubleArgumentType.getDouble(context, "amount"), true,
                                                database, logger)))))
                .then(Commands.literal("take")
                        .then(Commands.argument("player", EntityArgument.player())
                                .then(Commands.argument("amount", DoubleArgumentType.doubleArg(0.01))
                                        .executes(context -> adminAdjust(context.getSource(),
                                                EntityArgument.getPlayer(context, "player"),
                                                DoubleArgumentType.getDouble(context, "amount"), false,
                                                database, logger))))));
        dispatcher.register(Commands.literal("event")
                .executes(context -> eventStatus(context.getSource(), events))
                .then(Commands.literal("status")
                        .executes(context -> eventStatus(context.getSource(), events)))
                .then(Commands.literal("start").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("job", StringArgumentType.word())
                                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                        .then(Commands.argument("multiplier", DoubleArgumentType.doubleArg(1.0, 10.0))
                                                .executes(context -> startEvent(context.getSource(),
                                                        StringArgumentType.getString(context, "job"),
                                                        IntegerArgumentType.getInteger(context, "minutes"),
                                                        DoubleArgumentType.getDouble(context, "multiplier"), events))))))
                .then(Commands.literal("stop").requires(source -> source.hasPermission(2))
                        .executes(context -> stopEvent(context.getSource(), events))));
        dispatcher.register(Commands.literal("discord")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("test")
                        .executes(context -> testDiscord(context.getSource(), discord))));
        dispatcher.register(Commands.literal("craftrealm")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "CraftRealm Forge: /balance, /pay, /baltop, /bank, /market, /shop, /sell, /claim, /jobs, /quests, /season, /cosmetic, /event, /cradmin, /discord"), false);
                    return 1;
                }));
    }

    private static int balanceSelf(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = source.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null) {
            source.sendFailure(Component.literal("Console usage: /balance <player>"));
            return 0;
        }
        return showBalance(source, player.getUUID(), player.getGameProfile().getName(), database, logger);
    }

    private static int balanceOther(CommandSourceStack source, ServerPlayer target,
                                    Supplier<ForgeDatabase> database, Logger logger) {
        return showBalance(source, target.getUUID(), target.getGameProfile().getName(), database, logger);
    }

    private static int showBalance(CommandSourceStack source, UUID uuid, String name,
                                   Supplier<ForgeDatabase> database, Logger logger) {
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        store.balance(uuid, name, startingCents()).whenComplete((cents, error) -> source.getServer().execute(() -> {
            if (error != null) {
                reportFailure(source, error, logger);
                return;
            }
            source.sendSuccess(() -> Component.literal(name + "'s balance: " + format(cents)), false);
        }));
        return 1;
    }

    private static int pay(CommandSourceStack source, ServerPlayer recipient, double rawAmount,
                           Supplier<ForgeDatabase> database, Logger logger) {
        if (!(source.getEntity() instanceof ServerPlayer payer)) {
            source.sendFailure(Component.literal("Only players can pay another player."));
            return 0;
        }
        if (payer.getUUID().equals(recipient.getUUID())) {
            source.sendFailure(Component.literal("You cannot pay yourself."));
            return 0;
        }
        final long amountCents;
        try {
            amountCents = BigDecimal.valueOf(rawAmount).setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException invalidPrecision) {
            source.sendFailure(Component.literal("Payments must be finite and use at most two decimal places."));
            return 0;
        }
        long maxTransaction = toCents(ForgeConfig.MAX_TRANSACTION.get());
        if (amountCents <= 0 || amountCents > maxTransaction) {
            source.sendFailure(Component.literal("Payment is outside the configured transaction limits."));
            return 0;
        }
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        UUID payerId = payer.getUUID();
        UUID recipientId = recipient.getUUID();
        String payerName = payer.getGameProfile().getName();
        String recipientName = recipient.getGameProfile().getName();
        MinecraftServer server = source.getServer();
        store.balance(payerId, payerName, startingCents())
                .thenCombine(store.balance(recipientId, recipientName, startingCents()),
                        (payerBalance, recipientBalance) -> new long[]{payerBalance, recipientBalance})
                .whenComplete((balances, readFailure) -> server.execute(() -> {
                    if (readFailure != null) {
                        reportFailure(source, readFailure, logger);
                        return;
                    }
                    ForgeBalanceChangeEvent debit = new ForgeBalanceChangeEvent(payerId,
                            balances[0], balances[0] - amountCents, ForgeTransactionType.PAY,
                            "player payment", payerId);
                    ForgeBalanceChangeEvent credit = new ForgeBalanceChangeEvent(recipientId,
                            balances[1], balances[1] + amountCents, ForgeTransactionType.PAY,
                            "player payment", payerId);
                    if (MinecraftForge.EVENT_BUS.post(debit) || MinecraftForge.EVENT_BUS.post(credit)) {
                        source.sendFailure(Component.literal("Another mod cancelled this balance change."));
                        return;
                    }
                    store.pay(payerId, payerName, recipientId, recipientName, amountCents, startingCents(),
                                    toCents(ForgeConfig.MAX_BALANCE.get()))
                            .whenComplete((result, error) -> server.execute(() -> {
                                if (error != null) {
                                    reportFailure(source, error, logger);
                                    return;
                                }
                                if (result.insufficientFunds()) {
                                    source.sendFailure(Component.literal("Insufficient funds."));
                                } else if (result.recipientLimit()) {
                                    source.sendFailure(Component.literal("The recipient's balance is at its configured limit."));
                                } else if (result.paid()) {
                                    String amount = format(amountCents);
                                    source.sendSuccess(() -> Component.literal("Paid " + amount + " to " + recipientName
                                            + ". Balance: " + format(result.payerBalanceCents())), false);
                                    ServerPlayer onlineRecipient = server.getPlayerList().getPlayer(recipientId);
                                    if (onlineRecipient != null) {
                                        onlineRecipient.sendSystemMessage(Component.literal("Received " + amount + " from "
                                                + payerName + ". Balance: " + format(result.recipientBalanceCents())));
                                    }
                                }
                            }));
                }));
        return 1;
    }

    private static int testDiscord(CommandSourceStack source, Supplier<ForgeDiscordBridge> discordSupplier) {
        ForgeDiscordBridge bridge = discordSupplier.get();
        if (bridge == null || !bridge.configured()) {
            source.sendFailure(Component.literal("Discord webhook is not configured."));
            return 0;
        }
        bridge.send("CraftRealm Forge webhook test received.");
        source.sendSuccess(() -> Component.literal("Discord test notification queued."), true);
        return 1;
    }

    private static int eventStatus(CommandSourceStack source, Supplier<ForgeEventService> eventsSupplier) {
        ForgeEventService events = eventsSupplier.get();
        ForgeEventService.ActiveEvent active = events == null ? null : events.active();
        if (active == null) {
            source.sendSuccess(() -> Component.literal("No profession event is currently active."), false);
        } else {
            long seconds = Math.max(0L, (active.endsAtMillis() - System.currentTimeMillis()) / 1000L);
            source.sendSuccess(() -> Component.literal("Active event: " + active.job().toLowerCase(Locale.ROOT)
                    + " rewards ×" + active.multiplier() + " · " + seconds + " seconds remain."), false);
        }
        return 1;
    }

    private static int startEvent(CommandSourceStack source, String rawJob, int minutes, double multiplier,
                                  Supplier<ForgeEventService> eventsSupplier) {
        String job = rawJob.toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("ALL", "MINER", "FARMER", "HUNTER", "FISHER", "BUILDER").contains(job)) {
            source.sendFailure(Component.literal("Job must be all, miner, farmer, hunter, fisher, or builder."));
            return 0;
        }
        ForgeEventService events = eventsSupplier.get();
        if (events == null) {
            source.sendFailure(Component.literal("Event service is not available."));
            return 0;
        }
        events.start(source.getServer(), job, minutes, multiplier);
        return 1;
    }

    private static int stopEvent(CommandSourceStack source, Supplier<ForgeEventService> eventsSupplier) {
        ForgeEventService events = eventsSupplier.get();
        if (events == null || !events.stop(source.getServer())) {
            source.sendFailure(Component.literal("No profession event is active."));
            return 0;
        }
        return 1;
    }

    private static int adminAdjust(CommandSourceStack source, ServerPlayer target, double rawAmount,
                                   boolean credit, Supplier<ForgeDatabase> database, Logger logger) {
        long amount = parseCents(source, rawAmount);
        if (amount <= 0) return 0;
        if (amount > toCents(ForgeConfig.MAX_TRANSACTION.get())) {
            source.sendFailure(Component.literal("Amount exceeds the configured transaction limit."));
            return 0;
        }
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        UUID targetId = target.getUUID();
        UUID actor = source.getEntity() instanceof ServerPlayer staff ? staff.getUUID() : new UUID(0, 0);
        String targetName = target.getGameProfile().getName();
        MinecraftServer server = source.getServer();
        store.balance(targetId, targetName, startingCents()).whenComplete((oldBalance, readFailure) -> server.execute(() -> {
            if (readFailure != null) {
                reportFailure(source, readFailure, logger);
                return;
            }
            if (!credit && oldBalance < amount) {
                source.sendFailure(Component.literal("Target has insufficient funds."));
                return;
            }
            if (credit && oldBalance > toCents(ForgeConfig.MAX_BALANCE.get()) - amount) {
                source.sendFailure(Component.literal("The adjustment would exceed the configured balance limit."));
                return;
            }
            long newBalance = credit ? oldBalance + amount : oldBalance - amount;
            ForgeBalanceChangeEvent change = new ForgeBalanceChangeEvent(targetId, oldBalance, newBalance,
                    ForgeTransactionType.ADMIN_ADJUSTMENT, credit ? "admin grant" : "admin withdrawal", actor);
            if (MinecraftForge.EVENT_BUS.post(change)) {
                source.sendFailure(Component.literal("Another mod cancelled this balance change."));
                return;
            }
            store.adminAdjust(actor, targetId, targetName, amount, credit, startingCents(),
                            toCents(ForgeConfig.MAX_BALANCE.get()))
                    .whenComplete((result, failure) -> server.execute(() -> {
                        if (failure != null) reportFailure(source, failure, logger);
                        else if (!result.success()) source.sendFailure(Component.literal(
                                result.failure().equals("wallet-funds") ? "Target has insufficient funds."
                                        : "The adjustment would exceed a configured limit."));
                        else source.sendSuccess(() -> Component.literal((credit ? "Granted " : "Removed ")
                                + format(amount) + " " + (credit ? "to " : "from ") + targetName
                                + ". New balance: " + format(result.balanceCents())), true);
                    }));
        }));
        return 1;
    }

    private static int marketList(CommandSourceStack source, Supplier<ForgeMarket> marketSupplier, Logger logger) {
        ForgeMarket market = marketSupplier.get();
        if (market == null) {
            source.sendFailure(Component.literal("Market is still starting."));
            return 0;
        }
        market.quotes().whenComplete((quotes, failure) -> source.getServer().execute(() -> {
            if (failure != null) {
                reportFailure(source, failure, logger);
                return;
            }
            source.sendSuccess(() -> Component.literal("CraftRealm market · use /shop buy <item> <quantity> or /sell <item> <quantity>"), false);
            for (ForgeDatabase.MarketQuote quote : quotes) {
                source.sendSuccess(() -> Component.literal(quote.itemId() + "  " + format(quote.priceCents())
                        + " each  ·  traded " + quote.tradedUnits()), false);
            }
        }));
        return 1;
    }

    private static int marketQuote(CommandSourceStack source, String input,
                                   Supplier<ForgeMarket> marketSupplier, Logger logger) {
        ForgeMarket market = marketSupplier.get();
        String id = market == null ? null : market.itemId(input);
        if (id == null) {
            source.sendFailure(Component.literal("That item is not available in the configured market."));
            return 0;
        }
        market.quote(id).whenComplete((quote, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else if (quote == null) source.sendFailure(Component.literal("No market quote exists for that item."));
            else source.sendSuccess(() -> Component.literal(id + " costs " + format(quote.priceCents())
                    + " per item; base price " + format(quote.basePriceCents()) + "."), false);
        }));
        return 1;
    }

    private static int buyItem(CommandSourceStack source, String input, int quantity,
                               Supplier<ForgeMarket> marketSupplier, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeMarket market = marketSupplier.get();
        String id = market == null ? null : market.itemId(input);
        if (id == null) {
            source.sendFailure(Component.literal("That item is not available in the configured market."));
            return 0;
        }
        net.minecraft.world.item.Item item = market.item(id);
        UUID playerId = player.getUUID();
        String playerName = player.getGameProfile().getName();
        MinecraftServer server = source.getServer();
        market.trade(playerId, playerName, id, quantity, true, startingCents(),
                        toCents(ForgeConfig.MAX_BALANCE.get()))
                .whenComplete((trade, failure) -> server.execute(() -> {
                    if (failure != null) {
                        reportFailure(source, failure, logger);
                        return;
                    }
                    if (!trade.success()) {
                        source.sendFailure(Component.literal(trade.failure().equals("wallet-funds")
                                ? "Insufficient funds for this purchase." : "The market could not complete this purchase."));
                        return;
                    }
                    ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                    if (online == null) {
                        market.refund(playerId, trade.totalCents(), id, quantity).whenComplete((ignored, refundFailure) -> {
                            if (refundFailure != null) logger.error("Could not refund an undelivered shop purchase for {}", playerName, refundFailure);
                        });
                        return;
                    }
                    net.minecraft.world.item.ItemStack stack = new net.minecraft.world.item.ItemStack(item, quantity);
                    online.getInventory().add(stack);
                    if (!stack.isEmpty()) online.drop(stack.copy(), false);
                    online.sendSystemMessage(Component.literal("Bought " + quantity + " " + id + " for "
                            + format(trade.totalCents()) + ". Wallet: " + format(trade.walletCents())));
                }));
        return 1;
    }

    private static int sellItem(CommandSourceStack source, String input, int quantity,
                                Supplier<ForgeMarket> marketSupplier, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeMarket market = marketSupplier.get();
        String id = market == null ? null : market.itemId(input);
        net.minecraft.world.item.Item item = id == null || market == null ? null : market.item(id);
        if (id == null || item == null) {
            source.sendFailure(Component.literal("That item is not available in the configured market."));
            return 0;
        }
        java.util.List<net.minecraft.world.item.ItemStack> removed = takeItems(player, item, quantity);
        if (removed == null) {
            source.sendFailure(Component.literal("You do not have enough of that item."));
            return 0;
        }
        UUID playerId = player.getUUID();
        String playerName = player.getGameProfile().getName();
        MinecraftServer server = source.getServer();
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        net.minecraft.world.phys.Vec3 position = player.position();
        market.trade(playerId, playerName, id, quantity, false, startingCents(),
                        toCents(ForgeConfig.MAX_BALANCE.get()))
                .whenComplete((trade, failure) -> server.execute(() -> {
                    ServerPlayer online = server.getPlayerList().getPlayer(playerId);
                    if (failure != null || trade == null || !trade.success()) {
                        restoreItems(online, level, position, removed);
                        if (failure != null) reportFailure(source, failure, logger);
                        else source.sendFailure(Component.literal(trade != null && trade.failure().equals("wallet-limit")
                                ? "Selling would exceed your wallet limit; your items were returned."
                                : "The market could not complete this sale; your items were returned."));
                        return;
                    }
                    if (online != null) online.sendSystemMessage(Component.literal("Sold " + quantity + " " + id
                            + " for " + format(trade.totalCents()) + ". Wallet: " + format(trade.walletCents())));
                }));
        return 1;
    }

    private static java.util.List<net.minecraft.world.item.ItemStack> takeItems(ServerPlayer player,
                                                                                net.minecraft.world.item.Item item,
                                                                                int quantity) {
        int count = 0;
        for (int slot = 0; slot < 36; slot++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) count += stack.getCount();
        }
        if (count < quantity) return null;
        java.util.List<net.minecraft.world.item.ItemStack> removed = new java.util.ArrayList<>();
        int remaining = quantity;
        for (int slot = 0; slot < 36 && remaining > 0; slot++) {
            net.minecraft.world.item.ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(item)) continue;
            int amount = Math.min(remaining, stack.getCount());
            removed.add(stack.split(amount));
            remaining -= amount;
            if (stack.isEmpty()) player.getInventory().setItem(slot, net.minecraft.world.item.ItemStack.EMPTY);
        }
        return removed;
    }

    private static void restoreItems(ServerPlayer online, net.minecraft.server.level.ServerLevel level,
                                     net.minecraft.world.phys.Vec3 position,
                                     java.util.List<net.minecraft.world.item.ItemStack> stacks) {
        for (net.minecraft.world.item.ItemStack stack : stacks) {
            if (online != null) {
                online.getInventory().add(stack);
                if (!stack.isEmpty()) online.drop(stack.copy(), false);
            } else if (!stack.isEmpty()) {
                net.minecraft.world.entity.item.ItemEntity dropped = new net.minecraft.world.entity.item.ItemEntity(
                        level, position.x, position.y, position.z, stack);
                level.addFreshEntity(dropped);
            }
        }
    }

    private static int chooseJob(CommandSourceStack source, String rawJob,
                                 Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        String job = rawJob.toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("MINER", "FARMER", "HUNTER", "FISHER", "BUILDER").contains(job)) {
            source.sendFailure(Component.literal("Choose Miner, Farmer, Hunter, Fisher, or Builder."));
            return 0;
        }
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        store.chooseJob(player.getUUID(), job).whenComplete((selected, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else source.sendSuccess(() -> Component.literal("Your profession is now "
                    + job.substring(0, 1) + job.substring(1).toLowerCase(Locale.ROOT) + "."), false);
        }));
        return 1;
    }

    private static int seasonStatus(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        int days = ForgeConfig.SEASON_DURATION_DAYS.get();
        long now = System.currentTimeMillis();
        long seasonKey = Math.floorDiv(Math.floorDiv(now, 86_400_000L), days);
        long until = (seasonKey + 1L) * days * 86_400_000L - now;
        long remainingDays = Math.max(1, (until + 86_400_000L - 1) / 86_400_000L);
        store.seasonScore(player.getUUID(), seasonKey).whenComplete((score, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else source.sendSuccess(() -> Component.literal("Current season " + (seasonKey + 1)
                    + " · points " + score + " · " + remainingDays + " days remain. Use /season top."), false);
        }));
        return 1;
    }

    private static int seasonTop(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        int days = ForgeConfig.SEASON_DURATION_DAYS.get();
        long seasonKey = Math.floorDiv(Math.floorDiv(System.currentTimeMillis(), 86_400_000L), days);
        store.seasonTop(seasonKey, 10).whenComplete((ranks, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else {
                source.sendSuccess(() -> Component.literal("Season " + (seasonKey + 1) + " profession leaderboard:"), false);
                int[] rank = {1};
                for (ForgeDatabase.SeasonRank entry : ranks) {
                    int place = rank[0]++;
                    source.sendSuccess(() -> Component.literal(place + ". " + entry.name() + " · "
                            + entry.points() + " points"), false);
                }
            }
        }));
        return 1;
    }

    private static int cosmeticStatus(CommandSourceStack source, Supplier<ForgeCosmetics> cosmeticsSupplier) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeCosmetics cosmetics = cosmeticsSupplier.get();
        if (cosmetics == null) {
            source.sendFailure(Component.literal("Cosmetics service is starting."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Equipped trail: " + cosmetics.equipped(player.getUUID())
                + ". Available: " + String.join(", ", ForgeCosmetics.availableTrails())
                + ". Use /cosmetic equip <trail> or /cosmetic clear."), false);
        return 1;
    }

    private static int equipCosmetic(CommandSourceStack source, String trail,
                                    Supplier<ForgeCosmetics> cosmeticsSupplier, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeCosmetics cosmetics = cosmeticsSupplier.get();
        if (cosmetics == null) {
            source.sendFailure(Component.literal("Cosmetics service is starting."));
            return 0;
        }
        String normalized = trail.toUpperCase(Locale.ROOT);
        if (!normalized.equals("NONE") && !ForgeCosmetics.availableTrails().contains(normalized)) {
            source.sendFailure(Component.literal("Unknown trail. Choose: "
                    + String.join(", ", ForgeCosmetics.availableTrails()) + "."));
            return 0;
        }
        UUID playerId = player.getUUID();
        cosmetics.equip(playerId, normalized).whenComplete((ignored, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else source.sendSuccess(() -> Component.literal(normalized.equals("NONE")
                    ? "Cosmetic trail cleared." : "Equipped " + normalized.toLowerCase(Locale.ROOT) + " trail."), false);
        }));
        return 1;
    }

    private static int questStatus(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        int target = ForgeConfig.QUEST_ACTIONS_REQUIRED.get();
        store.questState(player.getUUID(), target).whenComplete((state, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else if (state.claimed()) source.sendSuccess(() -> Component.literal(
                    "Daily profession quest completed. A new quest is available after the daily reset."), false);
            else if (state.readyToClaim()) source.sendSuccess(() -> Component.literal(
                    "Daily profession quest complete! Claim " + format(toCents(ForgeConfig.QUEST_REWARD.get()))
                            + " with /quests claim."), false);
            else source.sendSuccess(() -> Component.literal("Daily profession quest: " + state.progress()
                    + "/" + state.actionsRequired() + " successful profession actions. Reward: "
                    + format(toCents(ForgeConfig.QUEST_REWARD.get())) + "."), false);
        }));
        return 1;
    }

    private static int claimQuest(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        long reward = toCents(ForgeConfig.QUEST_REWARD.get());
        store.claimQuest(player.getUUID(), player.getGameProfile().getName(),
                        ForgeConfig.QUEST_ACTIONS_REQUIRED.get(), reward, startingCents(),
                        toCents(ForgeConfig.MAX_BALANCE.get()))
                .whenComplete((result, failure) -> source.getServer().execute(() -> {
                    if (failure != null) reportFailure(source, failure, logger);
                    else if (!result.success()) source.sendFailure(Component.literal(switch (result.failure()) {
                        case "already-claimed" -> "You have already claimed today's quest reward.";
                        case "wallet-limit" -> "Your wallet is too close to its limit to claim the reward.";
                        default -> "Complete today's profession actions before claiming the reward.";
                    }));
                    else source.sendSuccess(() -> Component.literal("Daily quest reward claimed: "
                            + format(result.rewardCents()) + ". Wallet: " + format(result.balanceCents())), false);
                }));
        return 1;
    }

    private static int jobStatus(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        store.jobState(player.getUUID()).whenComplete((state, failure) -> source.getServer().execute(() -> {
            if (failure != null) {
                reportFailure(source, failure, logger);
            } else if (state.activeJob() == null) {
                source.sendSuccess(() -> Component.literal("Choose a profession with /jobs choose <miner|farmer|hunter|fisher|builder>."), false);
            } else {
                source.sendSuccess(() -> Component.literal(state.activeJob() + " · level " + state.level()
                        + " · XP " + state.xp() + " · earned today " + format(state.earnedTodayCents())), false);
            }
        }));
        return 1;
    }

    private static int claim(CommandSourceStack source, Supplier<ForgeClaims> claimsSupplier, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeClaims claims = claimsSupplier.get();
        if (claims == null || !claims.isReady()) {
            source.sendFailure(Component.literal("Claim protection is still starting."));
            return 0;
        }
        String dimension = player.level().dimension().location().toString();
        int chunkX = player.chunkPosition().x;
        int chunkZ = player.chunkPosition().z;
        long cost = toCents(ForgeConfig.CLAIM_COST.get());
        claims.createAsync(player, dimension, chunkX, chunkZ, cost, startingCents(),
                ForgeConfig.MAX_CLAIMS_PER_PLAYER.get(), result -> {
                    if (!result.success()) {
                        source.sendFailure(Component.literal(switch (result.failure()) {
                            case "already-claimed" -> "This chunk is already claimed.";
                            case "claim-limit" -> "You have reached the configured claim limit.";
                            case "insufficient-funds" -> "You cannot afford this claim.";
                            default -> "Claim could not be created. Check the server log.";
                        }));
                        if ("storage-error".equals(result.failure())) {
                            logger.error("Claim creation failed due to a storage error.");
                        }
                        return;
                    }
                    source.sendSuccess(() -> Component.literal("Claimed chunk " + chunkX + ", " + chunkZ
                            + " in " + dimension + " for " + format(cost) + ". Wallet: "
                            + format(result.walletCents())), false);
                });
        return 1;
    }

    private static int unclaim(CommandSourceStack source, Supplier<ForgeClaims> claimsSupplier) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeClaims claims = claimsSupplier.get();
        if (claims == null || !claims.isReady()) {
            source.sendFailure(Component.literal("Claim protection is still starting."));
            return 0;
        }
        String dimension = player.level().dimension().location().toString();
        int chunkX = player.chunkPosition().x;
        int chunkZ = player.chunkPosition().z;
        claims.removeAsync(player, dimension, chunkX, chunkZ, result -> {
            if (!result.success()) {
                source.sendFailure(Component.literal("You do not own a claim in this chunk."));
                return;
            }
            source.sendSuccess(() -> Component.literal("Unclaimed chunk " + chunkX + ", " + chunkZ + "."), false);
        });
        return 1;
    }

    private static int listClaims(CommandSourceStack source, Supplier<ForgeClaims> claimsSupplier) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeClaims claims = claimsSupplier.get();
        if (claims == null || !claims.isReady()) {
            source.sendFailure(Component.literal("Claim protection is still starting."));
            return 0;
        }
        var owned = claims.ownedBy(player.getUUID());
        source.sendSuccess(() -> Component.literal("Your claimed chunks: " + owned.size()), false);
        for (ForgeDatabase.Claim claim : owned) {
            source.sendSuccess(() -> Component.literal("- " + claim.dimension() + " ["
                    + claim.chunkX() + ", " + claim.chunkZ() + "]"), false);
        }
        return owned.size();
    }

    private static int bankBalance(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = source.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null) {
            source.sendFailure(Component.literal("Only players have a bank account."));
            return 0;
        }
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        store.bankBalance(uuid, name, startingCents()).whenComplete((account, error) -> source.getServer().execute(() -> {
            if (error != null) {
                reportFailure(source, error, logger);
                return;
            }
            source.sendSuccess(() -> Component.literal("Wallet: " + format(account.walletCents())
                    + "  Vault: " + format(account.bankCents())), false);
        }));
        return 1;
    }

    private static int bankMove(CommandSourceStack source, double rawAmount, boolean deposit,
                                Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        long amount = parseCents(source, rawAmount);
        if (amount <= 0) return 0;
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        store.moveBank(uuid, name, amount, deposit, startingCents(), toCents(ForgeConfig.MAX_BALANCE.get()))
                .whenComplete((result, error) -> source.getServer().execute(() -> {
                    if (error != null) {
                        reportFailure(source, error, logger);
                        return;
                    }
                    if (!result.success()) {
                        source.sendFailure(Component.literal(switch (result.failure()) {
                            case "wallet-funds" -> "Insufficient wallet funds.";
                            case "bank-funds" -> "Insufficient vault funds.";
                            default -> "The configured balance limit would be exceeded.";
                        }));
                        return;
                    }
                    ForgeDatabase.BankSnapshot account = result.balances();
                    source.sendSuccess(() -> Component.literal((deposit ? "Deposited " : "Withdrew ")
                            + format(amount) + ". Wallet: " + format(account.walletCents())
                            + "  Vault: " + format(account.bankCents())), false);
                }));
        return 1;
    }

    private static int takeLoan(CommandSourceStack source, double rawAmount,
                                Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        long amount = parseCents(source, rawAmount);
        if (amount <= 0) return 0;
        long limit = toCents(ForgeConfig.BANK_MAX_LOAN.get());
        if (amount > limit) {
            source.sendFailure(Component.literal("Requested amount exceeds the configured loan limit of " + format(limit) + "."));
            return 0;
        }
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        long term = ForgeConfig.BANK_LOAN_TERM_DAYS.get() * 86_400_000L;
        store.takeLoan(player.getUUID(), player.getGameProfile().getName(), amount, startingCents(),
                        toCents(ForgeConfig.MAX_BALANCE.get()), term)
                .whenComplete((result, error) -> source.getServer().execute(() -> {
                    if (error != null) {
                        reportFailure(source, error, logger);
                    } else if (!result.success()) {
                        source.sendFailure(Component.literal(result.failure().equals("loan-active")
                                ? "Repay your current loan before taking another."
                                : "The configured balance limit would be exceeded."));
                    } else {
                        source.sendSuccess(() -> Component.literal("Loan issued: " + format(amount)
                                + ". Due " + java.time.Instant.ofEpochMilli(result.dueAt())
                                .atZone(java.time.ZoneId.systemDefault()).toLocalDate() + "."), false);
                    }
                }));
        return 1;
    }

    private static int repayLoan(CommandSourceStack source, double rawAmount,
                                 Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        long amount = parseCents(source, rawAmount);
        if (amount <= 0) return 0;
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        store.repayLoan(player.getUUID(), player.getGameProfile().getName(), amount, startingCents())
                .whenComplete((result, error) -> source.getServer().execute(() -> {
                    if (error != null) {
                        reportFailure(source, error, logger);
                    } else if (!result.success()) {
                        source.sendFailure(Component.literal(result.failure().equals("loan-none")
                                ? "You have no outstanding loan."
                                : "Insufficient wallet funds to repay that amount."));
                    } else {
                        source.sendSuccess(() -> Component.literal("Repayment accepted. Remaining principal: "
                                + format(result.remainingCents()) + ". Wallet: " + format(result.walletCents())), false);
                    }
                }));
        return 1;
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) return player;
        source.sendFailure(Component.literal("This command is available to players only."));
        return null;
    }

    private static long parseCents(CommandSourceStack source, double rawAmount) {
        try {
            long cents = BigDecimal.valueOf(rawAmount).setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact();
            if (cents > 0) return cents;
        } catch (NumberFormatException | ArithmeticException ignored) {
            source.sendFailure(Component.literal("Amounts must be finite and use at most two decimal places."));
            return 0;
        }
        source.sendFailure(Component.literal("Amount must be positive."));
        return 0;
    }

    private static int baltop(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.literal("CraftRealm storage is still starting."));
            return 0;
        }
        store.top(10).whenComplete((accounts, error) -> source.getServer().execute(() -> {
            if (error != null) {
                reportFailure(source, error, logger);
                return;
            }
            source.sendSuccess(() -> Component.literal("Top balances — " + ForgeConfig.CURRENCY_NAME.get()), false);
            int rank = 1;
            for (ForgeDatabase.AccountBalance account : accounts) {
                int place = rank++;
                source.sendSuccess(() -> Component.literal(place + ". " + account.name() + "  "
                        + format(account.cents())), false);
            }
        }));
        return 1;
    }

    private static long startingCents() {
        return toCents(ForgeConfig.STARTING_BALANCE.get());
    }

    private static long toCents(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2).longValueExact();
    }

    private static String format(long cents) {
        return ForgeConfig.CURRENCY_SYMBOL.get() + String.format(Locale.ROOT, "%.2f", cents / 100.0);
    }

    private static void reportFailure(CommandSourceStack source, Throwable error, Logger logger) {
        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        logger.error("CraftRealm command database operation failed", cause);
        source.sendFailure(Component.literal("CraftRealm could not complete the request. Check the server log."));
    }
}
