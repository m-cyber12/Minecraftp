package io.craftrealm.core.jobs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobLevelCurveTest {

    @Test
    void firstLevelCostsTheBase() {
        assertEquals(100L, JobLevelCurve.xpForLevel(1, 100, 1.15));
    }

    @Test
    void eachLevelCostsMore() {
        long level5 = JobLevelCurve.xpForLevel(5, 100, 1.15);
        long level6 = JobLevelCurve.xpForLevel(6, 100, 1.15);
        assertTrue(level6 > level5);
        assertEquals(175L, level5);
    }

    @Test
    void multiplierStartsAtOneAndGrows() {
        assertEquals(1.0, JobLevelCurve.multiplier(1, 0.05), 0.0001);
        assertEquals(1.5, JobLevelCurve.multiplier(11, 0.05), 0.0001);
    }

    @Test
    void levelForXpWalksTheCurve() {
        // 100 (lvl1) + 115 (lvl2) = 215 → level 3.
        assertEquals(3, JobLevelCurve.levelForXp(215, 100, 1.15, 50));
        assertEquals(1, JobLevelCurve.levelForXp(0, 100, 1.15, 50));
    }

    @Test
    void rewardCalculatorHonoursTheDailyCap() {
        JobRewardCalculator calculator = new JobRewardCalculator(0.05, 100.0);
        assertEquals(1.5, calculator.rawPayout(1.0, 11, 1.0), 0.0001);
        assertEquals(1.5, calculator.cappedPayout(1.0, 11, 1.0, 98.0), 0.0001);
        assertEquals(0.0, calculator.cappedPayout(1.0, 11, 1.0, 100.0), 0.0001);
    }

    @Test
    void eventsMultiplyPayouts() {
        JobRewardCalculator calculator = new JobRewardCalculator(0.0, 0.0);
        assertEquals(4.0, calculator.rawPayout(2.0, 1, 2.0), 0.0001);
    }
}
