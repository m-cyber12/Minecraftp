package io.craftrealm.forge.command;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.craftrealm.forge.config.ForgeConfig;
import io.craftrealm.forge.storage.ForgeDatabase;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
        dispatcher.register(Commands.literal("craftrealm")
                .executes(context -> {
                    context.getSource().sendSuccess(() -> Component.literal(
                            "CraftRealm Forge: /balance, /pay, /baltop"), false);
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
        } catch (ArithmeticException invalidPrecision) {
            source.sendFailure(Component.literal("Payments must use at most two decimal places."));
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
        return 1;
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
