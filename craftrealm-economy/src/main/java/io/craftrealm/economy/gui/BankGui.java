package io.craftrealm.economy.gui;

import io.craftrealm.api.model.BankAccount;
import io.craftrealm.api.model.Loan;
import io.craftrealm.api.model.LoanStatus;
import io.craftrealm.core.bank.InterestCalculator;
import io.craftrealm.core.bank.LoanSchedule;
import io.craftrealm.core.ui.Gui;
import io.craftrealm.core.ui.GuiTheme;
import io.craftrealm.core.ui.ItemBuilder;
import io.craftrealm.core.util.Scheduler;
import io.craftrealm.core.util.TimeUtil;
import io.craftrealm.economy.EconomyContext;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;

/**
 * The bank vault.
 *
 * <p>Left-click moves 100 Crowns, shift-click moves everything. The interest tile
 * shows exactly when the next credit lands, and the loan tile quotes the total
 * repayment up-front so nobody borrows blind.</p>
 */
public final class BankGui extends Gui {

    private final EconomyContext context;
    private BankAccount account = BankAccount.empty(null);
    private List<Loan> loans = List.of();
    private double interest;
    private double wallet;
    private double loanLimit;

    public BankGui(EconomyContext context, Player player) {
        super(player, context.theme, 6, context.messages.get("bank.title"));
        this.context = context;
    }

    @Override
    public void open() {
        reload(() -> super.open());
    }

    private void reload(Runnable afterwards) {
        CompletableFuture<BankAccount> accountFuture = context.bank.account(viewer.getUniqueId());
        CompletableFuture<List<Loan>> loansFuture = context.bank.loans(viewer.getUniqueId());
        CompletableFuture<Double> interestFuture = context.bank.accruedInterest(viewer.getUniqueId());
        CompletableFuture<Double> walletFuture = context.economy.balance(viewer.getUniqueId());
        CompletableFuture<Double> limitFuture = context.bank.loanLimit(viewer.getUniqueId());
        CompletableFuture.allOf(accountFuture, loansFuture, interestFuture, walletFuture, limitFuture)
                .thenRun(() -> Scheduler.sync(() -> {
                    account = accountFuture.join();
                    loans = loansFuture.join();
                    interest = interestFuture.join();
                    wallet = walletFuture.join();
                    loanLimit = limitFuture.join();
                    if (afterwards != null) {
                        afterwards.run();
                    } else {
                        refresh();
                    }
                }));
    }

    @Override
    public void render() {
        set(4, ItemBuilder.of(Material.GOLD_BLOCK)
                .name(context.messages.raw("bank.vault.name"))
                .lore(context.messages.bundle().lines("bank.vault.lore",
                        "balance", context.money(account.balance()),
                        "wallet", context.money(wallet)))
                .glint(true)
                .build());

        set(19, ItemBuilder.of(Material.GOLD_INGOT)
                .name(context.messages.raw("bank.deposit.name"))
                .lore(context.messages.bundle().lines("bank.deposit.lore"))
                .build(), click -> move(true, click.shiftClick() ? wallet : Math.min(100.0, wallet)));

        set(21, ItemBuilder.of(Material.GOLD_NUGGET)
                .name(context.messages.raw("bank.withdraw.name"))
                .lore(context.messages.bundle().lines("bank.withdraw.lore"))
                .build(), click -> move(false, click.shiftClick() ? account.balance()
                        : Math.min(100.0, account.balance())));

        set(23, ItemBuilder.of(Material.CLOCK)
                .name(context.messages.raw("bank.interest.name"))
                .lore(context.messages.bundle().lines("bank.interest.lore",
                        "rate", String.format(java.util.Locale.ROOT, "%.2f",
                                context.settings.bank().interestRatePercent()),
                        "pending", context.money(interest),
                        "next", TimeUtil.relative(InterestCalculator.nextPayout(account.lastInterestAt(),
                                context.settings.bank().periodMillis()))))
                .build());

        renderLoans();
        fillEmpty();
    }

    private void renderLoans() {
        Loan active = loans.stream().filter(loan -> loan.status() == LoanStatus.ACTIVE)
                .findFirst().orElse(null);
        if (active == null) {
            List<Component> lore = context.messages.bundle().lines("bank.loan.none",
                    "limit", context.money(loanLimit));
            set(31, ItemBuilder.of(Material.WRITABLE_BOOK)
                    .name(context.messages.raw("bank.loan.name"))
                    .lore(lore)
                    .build(), click -> takeLoan());
            return;
        }
        set(31, ItemBuilder.of(Material.WRITTEN_BOOK)
                .name(context.messages.raw("bank.loan.active",
                        "amount", context.money(active.outstanding()),
                        "due", TimeUtil.formatDate(active.dueAt())))
                .lore(context.messages.bundle().lines("bank.loan.active-lore",
                        "outstanding", context.money(active.outstanding()),
                        "principal", context.money(active.principal()),
                        "due", TimeUtil.formatDate(active.dueAt()),
                        "remaining", TimeUtil.relative(active.dueAt())))
                .build(), click -> repay(active, click.shiftClick() ? active.outstanding()
                : Math.min(100.0, active.outstanding())));
    }

    private void move(boolean deposit, double amount) {
        if (amount <= 0.009) {
            theme.play(viewer, GuiTheme.Click.DENY);
            return;
        }
        var future = deposit ? context.bank.deposit(viewer.getUniqueId(), amount)
                : context.bank.withdraw(viewer.getUniqueId(), amount);
        future.thenAccept(result -> Scheduler.sync(() -> {
            if (!result.success()) {
                context.messages.send(viewer, result.messageKey());
                theme.play(viewer, GuiTheme.Click.DENY);
                reload(null);
                return;
            }
            context.messages.send(viewer, deposit ? "bank.deposited" : "bank.withdrew",
                    "amount", context.money(amount));
            theme.play(viewer, GuiTheme.Click.SUCCESS);
            reload(null);
        }));
    }

    private void takeLoan() {
        if (loanLimit <= 0) {
            context.messages.send(viewer, "bank.loan.not-eligible");
            theme.play(viewer, GuiTheme.Click.DENY);
            return;
        }
        context.bank.takeLoan(viewer.getUniqueId(), loanLimit).thenAccept(loan ->
                Scheduler.sync(() -> {
                    if (loan == null) {
                        context.messages.send(viewer, "bank.loan.denied");
                        theme.play(viewer, GuiTheme.Click.DENY);
                        return;
                    }
                    context.messages.send(viewer, "bank.loan.taken",
                            "amount", context.money(loan.principal()),
                            "owed", context.money(loan.outstanding()),
                            "due", TimeUtil.formatDate(loan.dueAt()));
                    theme.play(viewer, GuiTheme.Click.CONFIRM);
                    reload(null);
                }));
    }

    private void repay(Loan loan, double amount) {
        context.bank.repay(viewer.getUniqueId(), loan.id(), amount).thenAccept(result ->
                Scheduler.sync(() -> {
                    if (!result.success()) {
                        context.messages.send(viewer, result.messageKey());
                        theme.play(viewer, GuiTheme.Click.DENY);
                    } else {
                        context.messages.send(viewer, "bank.loan.repaid",
                                "amount", context.money(amount),
                                "remaining", context.money(Math.max(0, loan.outstanding() - amount)));
                        theme.play(viewer, GuiTheme.Click.PURCHASE);
                    }
                    reload(null);
                }));
    }
}
