package io.craftrealm.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void roundsHalfUpToTwoDecimals() {
        assertEquals(1.01, Money.round(1.005), 0.000001);
        assertEquals(2.35, Money.round(2.345), 0.000001);
        assertEquals(10.0, Money.round(9.999), 0.000001);
    }

    @Test
    void noneFiniteValuesBecomeZero() {
        assertEquals(0.0, Money.round(Double.NaN));
        assertEquals(0.0, Money.round(Double.POSITIVE_INFINITY));
    }

    @Test
    void rejectsInvalidAmounts() {
        assertFalse(Money.isValidAmount(0));
        assertFalse(Money.isValidAmount(-5));
        assertFalse(Money.isValidAmount(Double.NaN));
        assertTrue(Money.isValidAmount(0.01));
    }

    @Test
    void formatsWithSymbolAndGrouping() {
        assertEquals("1,250.50 ⛃", Money.format(1250.5, "⛃", 2, false));
        assertEquals("⛃ 1,250.50", Money.format(1250.5, "⛃", 2, true));
        assertEquals("7", Money.format(7, "", 0, false));
    }

    @Test
    void compactsLargeNumbers() {
        assertEquals("999", Money.compact(999));
        assertEquals("1.25k", Money.compact(1250));
        assertEquals("12.5k", Money.compact(12_500));
        assertEquals("1.50M", Money.compact(1_500_000));
    }

    @Test
    void parsesSuffixedInput() {
        assertEquals(1500.0, Money.parse("1.5k"), 0.000001);
        assertEquals(2_000_000.0, Money.parse("2M"), 0.000001);
        assertEquals(1234.0, Money.parse("1,234"), 0.000001);
        assertThrows(NumberFormatException.class, () -> Money.parse("not money"));
    }

    @Test
    void clampsWithinBounds() {
        assertEquals(5.0, Money.clamp(9, 0, 5));
        assertEquals(0.0, Money.clamp(-3, 0, 5));
    }
}
