package io.craftrealm.forge.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ForgeDatabaseTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsAndAtomicallyTransfersCents() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        try (ForgeDatabase database = new ForgeDatabase(temporaryDirectory.resolve("test.db"), 2,
                LoggerFactory.getLogger("forge-db-test"))) {
            database.open();
            assertEquals(10_000L, database.balance(alice, "Alice", 10_000).join());
            assertEquals(10_000L, database.balance(bob, "Bob", 10_000).join());

            ForgeDatabase.Payment paid = database.pay(alice, "Alice", bob, "Bob",
                    2_500, 10_000, 1_000_000).join();
            assertTrue(paid.paid());
            assertEquals(7_500L, paid.payerBalanceCents());
            assertEquals(12_500L, paid.recipientBalanceCents());

            ForgeDatabase.Payment declined = database.pay(alice, "Alice", bob, "Bob",
                    99_000, 10_000, 1_000_000).join();
            assertTrue(declined.insufficientFunds());
            assertEquals(7_500L, database.balance(alice, "Alice", 0).join());
        }
    }

    @Test
    void bankTransfersInterestAndLoansStayConsistent() throws Exception {
        UUID owner = UUID.randomUUID();
        try (ForgeDatabase database = new ForgeDatabase(temporaryDirectory.resolve("bank.db"), 1,
                LoggerFactory.getLogger("forge-bank-test"))) {
            database.open();
            assertTrue(database.moveBank(owner, "Owner", 5_000, true, 10_000, 1_000_000)
                    .join().success());
            ForgeDatabase.BankSnapshot snapshot = database.bankBalance(owner, "Owner", 0).join();
            assertEquals(5_000L, snapshot.walletCents());
            assertEquals(5_000L, snapshot.bankCents());

            int changed = database.processInterest(System.currentTimeMillis() + 86_400_000L,
                    86_400_000L, 10.0, 1_000_000).join();
            assertEquals(1, changed);
            assertEquals(5_500L, database.bankBalance(owner, "Owner", 0).join().bankCents());

            ForgeDatabase.LoanResult loan = database.takeLoan(owner, "Owner", 2_000,
                    0, 1_000_000, 100).join();
            assertTrue(loan.success());
            assertFalse(database.takeLoan(owner, "Owner", 1_000, 0, 1_000_000, 100).join().success());
            assertEquals(1, database.processOverdueLoans(System.currentTimeMillis() + 10_000L, 10.0).join());
            ForgeDatabase.LoanResult repaid = database.repayLoan(owner, "Owner", 10_000, 0).join();
            assertTrue(repaid.success());
            assertEquals(0L, repaid.remainingCents());
        }
    }

    @Test
    void claimsChargeTheOwnerAndCannotBeRemovedBySomeoneElse() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        try (ForgeDatabase database = new ForgeDatabase(temporaryDirectory.resolve("claims.db"), 1,
                LoggerFactory.getLogger("forge-claims-test"))) {
            database.open();
            ForgeDatabase.ClaimResult created = database.createClaim(owner, "Owner", "minecraft:overworld",
                    12, -3, 2_500, 10_000, 4).join();
            assertTrue(created.success());
            assertEquals(7_500L, created.walletCents());
            assertEquals(1, database.loadClaims().join().size());

            assertEquals("already-claimed", database.createClaim(stranger, "Stranger", "minecraft:overworld",
                    12, -3, 2_500, 0, 4).join().failure());
            assertEquals("not-owner", database.removeClaim(stranger, "minecraft:overworld", 12, -3)
                    .join().failure());
            assertTrue(database.removeClaim(owner, "minecraft:overworld", 12, -3).join().success());
            assertTrue(database.loadClaims().join().isEmpty());
        }
    }

    @Test
    void staffWalletChangesRespectLimitsAndRecordActor() throws Exception {
        UUID staff = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        try (ForgeDatabase database = new ForgeDatabase(temporaryDirectory.resolve("admin.db"), 1,
                LoggerFactory.getLogger("forge-admin-test"))) {
            database.open();
            database.balance(target, "Target", 1_000).join();
            ForgeDatabase.AdminAdjustment grant = database.adminAdjust(staff, target, "Target",
                    500, true, 1_000, 2_000).join();
            assertTrue(grant.success());
            assertEquals(1_500L, grant.balanceCents());
            assertEquals("wallet-funds", database.adminAdjust(staff, target, "Target",
                    2_000, false, 1_000, 2_000).join().failure());
            assertEquals("wallet-limit", database.adminAdjust(staff, target, "Target",
                    600, true, 1_000, 2_000).join().failure());
            assertEquals(1_500L, database.balance(target, "Target", 0).join());
        }
    }

    @Test
    void marketTradesUpdateWalletLedgerAndDynamicPricesAtomically() throws Exception {
        UUID trader = UUID.randomUUID();
        try (ForgeDatabase database = new ForgeDatabase(temporaryDirectory.resolve("market.db"), 1,
                LoggerFactory.getLogger("forge-market-test"))) {
            database.open();
            database.balance(trader, "Trader", 10_000).join();
            database.seedMarket(java.util.Map.of("minecraft:coal", 100L)).join();
            ForgeDatabase.MarketTrade purchase = database.tradeMarket(trader, "Trader", "minecraft:coal",
                    2, true, 10_000, 1_000_000).join();
            assertTrue(purchase.success());
            assertEquals(200L, purchase.totalCents());
            assertEquals(9_800L, purchase.walletCents());
            assertEquals(102L, purchase.nextPriceCents());

            ForgeDatabase.MarketTrade sale = database.tradeMarket(trader, "Trader", "minecraft:coal",
                    1, false, 10_000, 1_000_000).join();
            assertTrue(sale.success());
            assertEquals(102L, sale.totalCents());
            assertEquals(9_902L, sale.walletCents());
            assertEquals(101L, sale.nextPriceCents());
            assertEquals(3L, database.marketQuote("minecraft:coal").join().tradedUnits());

            UUID poor = UUID.randomUUID();
            database.balance(poor, "Poor", 0).join();
            assertEquals("wallet-funds", database.tradeMarket(poor, "Poor", "minecraft:coal",
                    1, true, 0, 1_000_000).join().failure());
        }
    }

    @Test
    void cosmeticTrailSelectionPersistsAndCanBeCleared() throws Exception {
        UUID player = UUID.randomUUID();
        try (ForgeDatabase database = new ForgeDatabase(temporaryDirectory.resolve("cosmetics.db"), 1,
                LoggerFactory.getLogger("forge-cosmetics-test"))) {
            database.open();
            database.setCosmetic(player, "HEART").join();
            assertEquals("HEART", database.cosmetic(player).join());
            assertEquals("HEART", database.loadCosmetics().join().get(player));
            database.setCosmetic(player, "NONE").join();
            assertNull(database.cosmetic(player).join());
        }
    }

    @Test
    void dailyProfessionQuestProgressAndRewardAreDurableAndSingleClaim() throws Exception {
        UUID worker = UUID.randomUUID();
        try (ForgeDatabase database = new ForgeDatabase(temporaryDirectory.resolve("quests.db"), 1,
                LoggerFactory.getLogger("forge-quests-test"))) {
            database.open();
            database.balance(worker, "Worker", 10_000).join();
            database.chooseJob(worker, "MINER").join();
            for (int i = 0; i < 2; i++) {
                assertTrue(database.awardJob(worker, "Worker", "MINER", 100,
                        10_000, 0.05, 4, 50, 10_000, 1_000_000).join().success());
            }
            ForgeDatabase.QuestState ready = database.questState(worker, 2).join();
            assertEquals(2, ready.progress());
            assertTrue(ready.readyToClaim());
            ForgeDatabase.QuestClaim reward = database.claimQuest(worker, "Worker", 2,
                    500, 10_000, 1_000_000).join();
            assertTrue(reward.success());
            assertEquals(500L, reward.rewardCents());
            assertEquals(10_700L, reward.balanceCents());
            assertEquals("already-claimed", database.claimQuest(worker, "Worker", 2,
                    500, 10_000, 1_000_000).join().failure());
        }
    }

    @Test
    void professionRewardsAreAtomicAndLevelUp() throws Exception {
        UUID worker = UUID.randomUUID();
        try (ForgeDatabase database = new ForgeDatabase(temporaryDirectory.resolve("jobs.db"), 1,
                LoggerFactory.getLogger("forge-jobs-test"))) {
            database.open();
            database.balance(worker, "Worker", 1_000).join();
            database.chooseJob(worker, "MINER").join();
            ForgeDatabase.JobState selected = database.jobState(worker).join();
            assertEquals("MINER", selected.activeJob());
            ForgeDatabase.JobAward award = database.awardJob(worker, "Worker", "MINER",
                    100, 10_000, 0.05, 100, 50, 0, 100_000).join();
            assertTrue(award.success());
            assertTrue(award.levelUp());
            assertEquals(2, award.level());
            assertEquals(0L, award.xp());
            assertEquals(1_100L, award.walletCents());
            assertEquals("not-employed", database.awardJob(worker, "Worker", "FARMER",
                    100, 10_000, 0.05, 100, 50, 0, 100_000).join().failure());
        }
    }
}
