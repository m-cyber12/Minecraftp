package io.craftrealm.forge.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.craftrealm.forge.api.ForgeBalanceChangeEvent;
import io.craftrealm.forge.api.ForgeTransactionType;
import io.craftrealm.forge.claims.ForgeClaims;
import io.craftrealm.forge.config.ForgeConfig;
import io.craftrealm.forge.cosmetics.ForgeCosmetics;
import io.craftrealm.forge.discord.ForgeDiscordBridge;
import io.craftrealm.forge.events.ForgeEventService;
import io.craftrealm.forge.gui.ForgeMenuService;
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
                                Supplier<ForgeDiscordBridge> discord, Supplier<ForgeMenuService> menus,
                                Logger logger) {
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
        dispatcher.register(Commands.literal("report")
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("reason", StringArgumentType.greedyString())
                                .executes(context -> submitReport(context.getSource(),
                                        EntityArgument.getPlayer(context, "player"),
                                        StringArgumentType.getString(context, "reason"), database, logger)))));
        dispatcher.register(Commands.literal("crreports")
                .requires(source -> source.hasPermission(2))
                .executes(context -> listReports(context.getSource(), database, logger)));
        dispatcher.register(Commands.literal("crresolve")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("id", LongArgumentType.longArg(1))
                        .executes(context -> resolveReport(context.getSource(),
                                LongArgumentType.getLong(context, "id"), database, logger))));
        dispatcher.register(Commands.literal("crnote")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("note", StringArgumentType.greedyString())
                                .executes(context -> addStaffNote(context.getSource(),
                                        EntityArgument.getPlayer(context, "player"),
                                        StringArgumentType.getString(context, "note"), database, logger)))));
        dispatcher.register(Commands.literal("crnotes")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(context -> listStaffNotes(context.getSource(),
                                EntityArgument.getPlayer(context, "player"), database, logger))));
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
        dispatcher.register(Commands.literal("crmenu")
                .executes(context -> openMenu(context.getSource(), menus)));
        dispatcher.register(Commands.literal("discord")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("test")
                        .executes(context -> testDiscord(context.getSource(), discord))));
        dispatcher.register(Commands.literal("craftrealm")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.translatable("craftrealm.help.commands"), false);
                    return 1;
                }));
    }

    private static int balanceSelf(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = source.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null) {
            source.sendFailure(Component.translatable("craftrealm.error.console_balance_usage"));
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
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        store.balance(uuid, name, startingCents()).whenComplete((cents, error) -> source.getServer().execute(() -> {
            if (error != null) {
                reportFailure(source, error, logger);
                return;
            }
            source.sendSuccess(() -> Component.translatable("craftrealm.balance.result", name, format(cents)), false);
        }));
        return 1;
    }

    private static int pay(CommandSourceStack source, ServerPlayer recipient, double rawAmount,
                           Supplier<ForgeDatabase> database, Logger logger) {
        if (!(source.getEntity() instanceof ServerPlayer payer)) {
            source.sendFailure(Component.translatable("craftrealm.error.players_only_pay"));
            return 0;
        }
        if (payer.getUUID().equals(recipient.getUUID())) {
            source.sendFailure(Component.translatable("craftrealm.error.pay_self"));
            return 0;
        }
        final long amountCents;
        try {
            amountCents = BigDecimal.valueOf(rawAmount).setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException invalidPrecision) {
            source.sendFailure(Component.translatable("craftrealm.error.invalid_precision"));
            return 0;
        }
        long maxTransaction = toCents(ForgeConfig.MAX_TRANSACTION.get());
        if (amountCents <= 0 || amountCents > maxTransaction) {
            source.sendFailure(Component.translatable("craftrealm.error.transaction_limit"));
            return 0;
        }
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
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
                        source.sendFailure(Component.translatable("craftrealm.error.balance_cancelled"));
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
                                    source.sendFailure(Component.translatable("craftrealm.error.insufficient_funds"));
                                } else if (result.recipientLimit()) {
                                    source.sendFailure(Component.translatable("craftrealm.error.recipient_limit"));
                                } else if (result.paid()) {
                                    String amount = format(amountCents);
                                    source.sendSuccess(() -> Component.translatable("craftrealm.pay.sent", amount, recipientName,
                                            format(result.payerBalanceCents())), false);
                                    ServerPlayer onlineRecipient = server.getPlayerList().getPlayer(recipientId);
                                    if (onlineRecipient != null) {
                                        onlineRecipient.sendSystemMessage(Component.translatable("craftrealm.pay.received", amount, payerName,
                                                format(result.recipientBalanceCents())));
                                    }
                                }
                            }));
                }));
        return 1;
    }

    private static int openMenu(CommandSourceStack source, Supplier<ForgeMenuService> menusSupplier) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeMenuService menus = menusSupplier.get();
        if (menus == null) {
            source.sendFailure(Component.translatable("craftrealm.error.menu_unavailable"));
            return 0;
        }
        menus.open(player);
        return 1;
    }

    private static int testDiscord(CommandSourceStack source, Supplier<ForgeDiscordBridge> discordSupplier) {
        ForgeDiscordBridge bridge = discordSupplier.get();
        if (bridge == null || !bridge.configured()) {
            source.sendFailure(Component.translatable("craftrealm.error.discord_unconfigured"));
            return 0;
        }
        bridge.send("CraftRealm Forge webhook test received.");
        source.sendSuccess(() -> Component.translatable("craftrealm.discord.test_queued"), true);
        return 1;
    }

    private static int eventStatus(CommandSourceStack source, Supplier<ForgeEventService> eventsSupplier) {
        ForgeEventService events = eventsSupplier.get();
        ForgeEventService.ActiveEvent active = events == null ? null : events.active();
        if (active == null) {
            source.sendSuccess(() -> Component.translatable("craftrealm.event.none"), false);
        } else {
            long seconds = Math.max(0L, (active.endsAtMillis() - System.currentTimeMillis()) / 1000L);
            source.sendSuccess(() -> Component.translatable("craftrealm.event.active", active.job().toLowerCase(Locale.ROOT),
                    active.multiplier(), seconds), false);
        }
        return 1;
    }

    private static int startEvent(CommandSourceStack source, String rawJob, int minutes, double multiplier,
                                  Supplier<ForgeEventService> eventsSupplier) {
        String job = rawJob.toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("ALL", "MINER", "FARMER", "HUNTER", "FISHER", "BUILDER").contains(job)) {
            source.sendFailure(Component.translatable("craftrealm.event.invalid_job"));
            return 0;
        }
        ForgeEventService events = eventsSupplier.get();
        if (events == null) {
            source.sendFailure(Component.translatable("craftrealm.error.event_unavailable"));
            return 0;
        }
        events.start(source.getServer(), job, minutes, multiplier);
        return 1;
    }

    private static int stopEvent(CommandSourceStack source, Supplier<ForgeEventService> eventsSupplier) {
        ForgeEventService events = eventsSupplier.get();
        if (events == null || !events.stop(source.getServer())) {
            source.sendFailure(Component.translatable("craftrealm.event.none"));
            return 0;
        }
        return 1;
    }

    private static int submitReport(CommandSourceStack source, ServerPlayer target, String reason,
                                    Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer reporter = requirePlayer(source);
        if (reporter == null) return 0;
        if (reporter.getUUID().equals(target.getUUID())) {
            source.sendFailure(Component.translatable("craftrealm.report.self"));
            return 0;
        }
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        String reporterName = reporter.getGameProfile().getName();
        String targetName = target.getGameProfile().getName();
        store.submitReport(reporter.getUUID(), reporterName, target.getUUID(), targetName, reason)
                .whenComplete((result, failure) -> source.getServer().execute(() -> {
                    if (failure != null) reportFailure(source, failure, logger);
                    else if (!result.success()) source.sendFailure(Component.translatable(
                            result.failure().equals("cooldown") ? "craftrealm.report.cooldown" : "craftrealm.report.invalid"));
                    else source.sendSuccess(() -> Component.translatable(
                            "craftrealm.report.submitted", result.id(), targetName), false);
                }));
        return 1;
    }

    private static int listReports(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        store.openReports(25).whenComplete((reports, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else {
                source.sendSuccess(() -> Component.translatable("craftrealm.report.list_header", reports.size()), false);
                for (ForgeDatabase.Report report : reports) {
                    source.sendSuccess(() -> Component.translatable("craftrealm.report.row", report.id(),
                            report.reporter(), report.target(), report.reason()), false);
                }
            }
        }));
        return 1;
    }

    private static int resolveReport(CommandSourceStack source, long reportId,
                                    Supplier<ForgeDatabase> database, Logger logger) {
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        UUID staff = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : new UUID(0, 0);
        store.resolveReport(reportId, staff).whenComplete((resolved, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else if (resolved) source.sendSuccess(() -> Component.translatable("craftrealm.report.resolved", reportId), true);
            else source.sendFailure(Component.translatable("craftrealm.report.not_open", reportId));
        }));
        return 1;
    }

    private static int addStaffNote(CommandSourceStack source, ServerPlayer target, String note,
                                    Supplier<ForgeDatabase> database, Logger logger) {
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        UUID staff = source.getEntity() instanceof ServerPlayer player ? player.getUUID() : new UUID(0, 0);
        String staffName = source.getEntity() instanceof ServerPlayer player
                ? player.getGameProfile().getName() : "Console";
        String targetName = target.getGameProfile().getName();
        store.addStaffNote(staff, staffName, target.getUUID(), targetName, note)
                .whenComplete((ignored, failure) -> source.getServer().execute(() -> {
                    if (failure != null) reportFailure(source, failure, logger);
                    else source.sendSuccess(() -> Component.translatable("craftrealm.notes.added", targetName), true);
                }));
        return 1;
    }

    private static int listStaffNotes(CommandSourceStack source, ServerPlayer target,
                                     Supplier<ForgeDatabase> database, Logger logger) {
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        String targetName = target.getGameProfile().getName();
        store.staffNotes(target.getUUID(), 20).whenComplete((notes, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else {
                source.sendSuccess(() -> Component.translatable("craftrealm.notes.header", targetName, notes.size()), true);
                for (ForgeDatabase.StaffNote note : notes) {
                    source.sendSuccess(() -> Component.translatable("craftrealm.notes.row", note.staff(), note.note()), true);
                }
            }
        }));
        return 1;
    }

    private static int adminAdjust(CommandSourceStack source, ServerPlayer target, double rawAmount,
                                   boolean credit, Supplier<ForgeDatabase> database, Logger logger) {
        long amount = parseCents(source, rawAmount);
        if (amount <= 0) return 0;
        if (amount > toCents(ForgeConfig.MAX_TRANSACTION.get())) {
            source.sendFailure(Component.translatable("craftrealm.error.transaction_limit"));
            return 0;
        }
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
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
                source.sendFailure(Component.translatable("craftrealm.error.target_funds"));
                return;
            }
            if (credit && oldBalance > toCents(ForgeConfig.MAX_BALANCE.get()) - amount) {
                source.sendFailure(Component.translatable("craftrealm.error.balance_limit"));
                return;
            }
            long newBalance = credit ? oldBalance + amount : oldBalance - amount;
            ForgeBalanceChangeEvent change = new ForgeBalanceChangeEvent(targetId, oldBalance, newBalance,
                    ForgeTransactionType.ADMIN_ADJUSTMENT, credit ? "admin grant" : "admin withdrawal", actor);
            if (MinecraftForge.EVENT_BUS.post(change)) {
                source.sendFailure(Component.translatable("craftrealm.error.balance_cancelled"));
                return;
            }
            store.adminAdjust(actor, targetId, targetName, amount, credit, startingCents(),
                            toCents(ForgeConfig.MAX_BALANCE.get()))
                    .whenComplete((result, failure) -> server.execute(() -> {
                        if (failure != null) reportFailure(source, failure, logger);
                        else if (!result.success()) source.sendFailure(Component.translatable(result.failure().equals("wallet-funds")
                                ? "craftrealm.error.target_funds" : "craftrealm.error.balance_limit"));
                        else source.sendSuccess(() -> Component.translatable(credit ? "craftrealm.admin.granted" : "craftrealm.admin.removed",
                                format(amount), targetName, format(result.balanceCents())), true);
                    }));
        }));
        return 1;
    }

    private static int marketList(CommandSourceStack source, Supplier<ForgeMarket> marketSupplier, Logger logger) {
        ForgeMarket market = marketSupplier.get();
        if (market == null) {
            source.sendFailure(Component.translatable("craftrealm.error.market_starting"));
            return 0;
        }
        market.quotes().whenComplete((quotes, failure) -> source.getServer().execute(() -> {
            if (failure != null) {
                reportFailure(source, failure, logger);
                return;
            }
            source.sendSuccess(() -> Component.translatable("craftrealm.market.header"), false);
            for (ForgeDatabase.MarketQuote quote : quotes) {
                source.sendSuccess(() -> Component.translatable("craftrealm.market.row", quote.itemId(),
                        format(quote.priceCents()), quote.tradedUnits()), false);
            }
        }));
        return 1;
    }

    private static int marketQuote(CommandSourceStack source, String input,
                                   Supplier<ForgeMarket> marketSupplier, Logger logger) {
        ForgeMarket market = marketSupplier.get();
        String id = market == null ? null : market.itemId(input);
        if (id == null) {
            source.sendFailure(Component.translatable("craftrealm.error.item_unavailable"));
            return 0;
        }
        market.quote(id).whenComplete((quote, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else if (quote == null) source.sendFailure(Component.translatable("craftrealm.error.quote_missing"));
            else source.sendSuccess(() -> Component.translatable("craftrealm.market.quote", id,
                    format(quote.priceCents()), format(quote.basePriceCents())), false);
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
            source.sendFailure(Component.translatable("craftrealm.error.item_unavailable"));
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
                        source.sendFailure(Component.translatable(trade.failure().equals("wallet-funds")
                                ? "craftrealm.error.insufficient_funds" : "craftrealm.error.market_failed"));
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
                    online.sendSystemMessage(Component.translatable("craftrealm.market.bought", quantity, id,
                            format(trade.totalCents()), format(trade.walletCents())));
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
            source.sendFailure(Component.translatable("craftrealm.error.item_unavailable"));
            return 0;
        }
        java.util.List<net.minecraft.world.item.ItemStack> removed = takeItems(player, item, quantity);
        if (removed == null) {
            source.sendFailure(Component.translatable("craftrealm.error.item_shortage"));
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
                        else source.sendFailure(Component.translatable(trade != null && trade.failure().equals("wallet-limit")
                                ? "craftrealm.error.sell_wallet_limit" : "craftrealm.error.market_sale_failed"));
                        return;
                    }
                    if (online != null) online.sendSystemMessage(Component.translatable("craftrealm.market.sold", quantity, id,
                            format(trade.totalCents()), format(trade.walletCents())));
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
            source.sendFailure(Component.translatable("craftrealm.jobs.invalid_choice"));
            return 0;
        }
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        store.chooseJob(player.getUUID(), job).whenComplete((selected, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else source.sendSuccess(() -> Component.translatable("craftrealm.jobs.selected",
                    job.substring(0, 1) + job.substring(1).toLowerCase(Locale.ROOT)), false);
        }));
        return 1;
    }

    private static int seasonStatus(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        int days = ForgeConfig.SEASON_DURATION_DAYS.get();
        long now = System.currentTimeMillis();
        long seasonKey = Math.floorDiv(Math.floorDiv(now, 86_400_000L), days);
        long until = (seasonKey + 1L) * days * 86_400_000L - now;
        long remainingDays = Math.max(1, (until + 86_400_000L - 1) / 86_400_000L);
        store.seasonScore(player.getUUID(), seasonKey).whenComplete((score, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else source.sendSuccess(() -> Component.translatable("craftrealm.season.status", seasonKey + 1, score, remainingDays), false);
        }));
        return 1;
    }

    private static int seasonTop(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        int days = ForgeConfig.SEASON_DURATION_DAYS.get();
        long seasonKey = Math.floorDiv(Math.floorDiv(System.currentTimeMillis(), 86_400_000L), days);
        store.seasonTop(seasonKey, 10).whenComplete((ranks, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else {
                source.sendSuccess(() -> Component.translatable("craftrealm.season.header", seasonKey + 1), false);
                int[] rank = {1};
                for (ForgeDatabase.SeasonRank entry : ranks) {
                    int place = rank[0]++;
                    source.sendSuccess(() -> Component.translatable("craftrealm.season.row", place, entry.name(), entry.points()), false);
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
            source.sendFailure(Component.translatable("craftrealm.error.cosmetics_starting"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("craftrealm.cosmetic.status", cosmetics.equipped(player.getUUID()),
                String.join(", ", ForgeCosmetics.availableTrails())), false);
        return 1;
    }

    private static int equipCosmetic(CommandSourceStack source, String trail,
                                    Supplier<ForgeCosmetics> cosmeticsSupplier, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeCosmetics cosmetics = cosmeticsSupplier.get();
        if (cosmetics == null) {
            source.sendFailure(Component.translatable("craftrealm.error.cosmetics_starting"));
            return 0;
        }
        String normalized = trail.toUpperCase(Locale.ROOT);
        if (!normalized.equals("NONE") && !ForgeCosmetics.availableTrails().contains(normalized)) {
            source.sendFailure(Component.translatable("craftrealm.cosmetic.unknown", String.join(", ", ForgeCosmetics.availableTrails())));
            return 0;
        }
        UUID playerId = player.getUUID();
        cosmetics.equip(playerId, normalized).whenComplete((ignored, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else source.sendSuccess(() -> Component.translatable(normalized.equals("NONE")
                    ? "craftrealm.cosmetic.cleared" : "craftrealm.cosmetic.equipped",
                    normalized.toLowerCase(Locale.ROOT)), false);
        }));
        return 1;
    }

    private static int questStatus(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        int target = ForgeConfig.QUEST_ACTIONS_REQUIRED.get();
        store.questState(player.getUUID(), target).whenComplete((state, failure) -> source.getServer().execute(() -> {
            if (failure != null) reportFailure(source, failure, logger);
            else if (state.claimed()) source.sendSuccess(() -> Component.translatable("craftrealm.quest.claimed_today"), false);
            else if (state.readyToClaim()) source.sendSuccess(() -> Component.translatable("craftrealm.quest.ready", format(toCents(ForgeConfig.QUEST_REWARD.get()))), false);
            else source.sendSuccess(() -> Component.translatable("craftrealm.quest.progress", state.progress(), state.actionsRequired(),
                    format(toCents(ForgeConfig.QUEST_REWARD.get()))), false);
        }));
        return 1;
    }

    private static int claimQuest(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        long reward = toCents(ForgeConfig.QUEST_REWARD.get());
        store.claimQuest(player.getUUID(), player.getGameProfile().getName(),
                        ForgeConfig.QUEST_ACTIONS_REQUIRED.get(), reward, startingCents(),
                        toCents(ForgeConfig.MAX_BALANCE.get()))
                .whenComplete((result, failure) -> source.getServer().execute(() -> {
                    if (failure != null) reportFailure(source, failure, logger);
                    else if (!result.success()) source.sendFailure(Component.translatable(switch (result.failure()) {
                        case "already-claimed" -> "craftrealm.quest.already_claimed";
                        case "wallet-limit" -> "craftrealm.quest.wallet_limit";
                        default -> "craftrealm.quest.not_ready";
                    }));
                    else source.sendSuccess(() -> Component.translatable("craftrealm.quest.reward_claimed", format(result.rewardCents()),
                            format(result.balanceCents())), false);
                }));
        return 1;
    }

    private static int jobStatus(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        store.jobState(player.getUUID()).whenComplete((state, failure) -> source.getServer().execute(() -> {
            if (failure != null) {
                reportFailure(source, failure, logger);
            } else if (state.activeJob() == null) {
                source.sendSuccess(() -> Component.translatable("craftrealm.jobs.choose_prompt"), false);
            } else {
                source.sendSuccess(() -> Component.translatable("craftrealm.jobs.status", state.activeJob(), state.level(),
                        state.xp(), format(state.earnedTodayCents())), false);
            }
        }));
        return 1;
    }

    private static int claim(CommandSourceStack source, Supplier<ForgeClaims> claimsSupplier, Logger logger) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeClaims claims = claimsSupplier.get();
        if (claims == null || !claims.isReady()) {
            source.sendFailure(Component.translatable("craftrealm.error.claims_starting"));
            return 0;
        }
        String dimension = player.level().dimension().location().toString();
        int chunkX = player.chunkPosition().x;
        int chunkZ = player.chunkPosition().z;
        long cost = toCents(ForgeConfig.CLAIM_COST.get());
        claims.createAsync(player, dimension, chunkX, chunkZ, cost, startingCents(),
                ForgeConfig.MAX_CLAIMS_PER_PLAYER.get(), result -> {
                    if (!result.success()) {
                        source.sendFailure(Component.translatable(switch (result.failure()) {
                            case "already-claimed" -> "craftrealm.claim.already_claimed";
                            case "claim-limit" -> "craftrealm.claim.limit";
                            case "insufficient-funds" -> "craftrealm.claim.funds";
                            default -> "craftrealm.claim.create_failed";
                        }));
                        if ("storage-error".equals(result.failure())) {
                            logger.error("Claim creation failed due to a storage error.");
                        }
                        return;
                    }
                    source.sendSuccess(() -> Component.translatable("craftrealm.claim.created", chunkX, chunkZ, dimension,
                            format(cost), format(result.walletCents())), false);
                });
        return 1;
    }

    private static int unclaim(CommandSourceStack source, Supplier<ForgeClaims> claimsSupplier) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeClaims claims = claimsSupplier.get();
        if (claims == null || !claims.isReady()) {
            source.sendFailure(Component.translatable("craftrealm.error.claims_starting"));
            return 0;
        }
        String dimension = player.level().dimension().location().toString();
        int chunkX = player.chunkPosition().x;
        int chunkZ = player.chunkPosition().z;
        claims.removeAsync(player, dimension, chunkX, chunkZ, result -> {
            if (!result.success()) {
                source.sendFailure(Component.translatable("craftrealm.error.claim_not_owner"));
                return;
            }
            source.sendSuccess(() -> Component.translatable("craftrealm.claim.removed", chunkX, chunkZ), false);
        });
        return 1;
    }

    private static int listClaims(CommandSourceStack source, Supplier<ForgeClaims> claimsSupplier) {
        ServerPlayer player = requirePlayer(source);
        if (player == null) return 0;
        ForgeClaims claims = claimsSupplier.get();
        if (claims == null || !claims.isReady()) {
            source.sendFailure(Component.translatable("craftrealm.error.claims_starting"));
            return 0;
        }
        var owned = claims.ownedBy(player.getUUID());
        source.sendSuccess(() -> Component.translatable("craftrealm.claim.list_header", owned.size()), false);
        for (ForgeDatabase.Claim claim : owned) {
            source.sendSuccess(() -> Component.translatable("craftrealm.claim.row", claim.dimension(), claim.chunkX(), claim.chunkZ()), false);
        }
        return owned.size();
    }

    private static int bankBalance(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ServerPlayer player = source.getEntity() instanceof ServerPlayer serverPlayer ? serverPlayer : null;
        if (player == null) {
            source.sendFailure(Component.translatable("craftrealm.error.bank_players_only"));
            return 0;
        }
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        store.bankBalance(uuid, name, startingCents()).whenComplete((account, error) -> source.getServer().execute(() -> {
            if (error != null) {
                reportFailure(source, error, logger);
                return;
            }
            source.sendSuccess(() -> Component.translatable("craftrealm.bank.balance", format(account.walletCents()),
                    format(account.bankCents())), false);
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
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
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
                        source.sendFailure(Component.translatable(switch (result.failure()) {
                            case "wallet-funds" -> "craftrealm.bank.wallet_funds";
                            case "bank-funds" -> "craftrealm.bank.vault_funds";
                            default -> "craftrealm.error.balance_limit";
                        }));
                        return;
                    }
                    ForgeDatabase.BankSnapshot account = result.balances();
                    source.sendSuccess(() -> Component.translatable(deposit ? "craftrealm.bank.deposited" : "craftrealm.bank.withdrew",
                            format(amount), format(account.walletCents()), format(account.bankCents())), false);
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
            source.sendFailure(Component.translatable("craftrealm.bank.loan_limit", format(limit)));
            return 0;
        }
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        long term = ForgeConfig.BANK_LOAN_TERM_DAYS.get() * 86_400_000L;
        store.takeLoan(player.getUUID(), player.getGameProfile().getName(), amount, startingCents(),
                        toCents(ForgeConfig.MAX_BALANCE.get()), term)
                .whenComplete((result, error) -> source.getServer().execute(() -> {
                    if (error != null) {
                        reportFailure(source, error, logger);
                    } else if (!result.success()) {
                        source.sendFailure(Component.translatable(result.failure().equals("loan-active")
                                ? "craftrealm.bank.loan_active" : "craftrealm.error.balance_limit"));
                    } else {
                        source.sendSuccess(() -> Component.translatable("craftrealm.bank.loan_issued", format(amount),
                                java.time.Instant.ofEpochMilli(result.dueAt())
                                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()), false);
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
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        store.repayLoan(player.getUUID(), player.getGameProfile().getName(), amount, startingCents())
                .whenComplete((result, error) -> source.getServer().execute(() -> {
                    if (error != null) {
                        reportFailure(source, error, logger);
                    } else if (!result.success()) {
                        source.sendFailure(Component.translatable(result.failure().equals("loan-none")
                                ? "craftrealm.bank.loan_none" : "craftrealm.bank.wallet_funds"));
                    } else {
                        source.sendSuccess(() -> Component.translatable("craftrealm.bank.repaid", format(result.remainingCents()),
                                format(result.walletCents())), false);
                    }
                }));
        return 1;
    }

    private static ServerPlayer requirePlayer(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) return player;
        source.sendFailure(Component.translatable("craftrealm.error.players_only"));
        return null;
    }

    private static long parseCents(CommandSourceStack source, double rawAmount) {
        try {
            long cents = BigDecimal.valueOf(rawAmount).setScale(2, RoundingMode.UNNECESSARY)
                    .movePointRight(2).longValueExact();
            if (cents > 0) return cents;
        } catch (NumberFormatException | ArithmeticException ignored) {
            source.sendFailure(Component.translatable("craftrealm.error.invalid_precision"));
            return 0;
        }
        source.sendFailure(Component.translatable("craftrealm.error.amount_positive"));
        return 0;
    }

    private static int baltop(CommandSourceStack source, Supplier<ForgeDatabase> database, Logger logger) {
        ForgeDatabase store = database.get();
        if (store == null) {
            source.sendFailure(Component.translatable("craftrealm.error.storage_starting"));
            return 0;
        }
        store.top(10).whenComplete((accounts, error) -> source.getServer().execute(() -> {
            if (error != null) {
                reportFailure(source, error, logger);
                return;
            }
            source.sendSuccess(() -> Component.translatable("craftrealm.balance.top_header", ForgeConfig.CURRENCY_NAME.get()), false);
            int rank = 1;
            for (ForgeDatabase.AccountBalance account : accounts) {
                int place = rank++;
                source.sendSuccess(() -> Component.translatable("craftrealm.balance.top_row", place, account.name(),
                        format(account.cents())), false);
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
        source.sendFailure(Component.translatable("craftrealm.error.database_failure"));
    }
}
