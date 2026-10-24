package io.craftrealm.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeUtilTest {

    @Test
    void parsesCompoundDurations() {
        assertEquals(90_000L, TimeUtil.parseDuration("90s"));
        assertEquals(90_000L, TimeUtil.parseDuration("1m30s"));
        assertEquals(3_600_000L, TimeUtil.parseDuration("1h"));
        assertEquals(90_061_000L, TimeUtil.parseDuration("1d1h1m1s"));
        assertEquals(45_000L, TimeUtil.parseDuration("45"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseDuration("soon"));
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseDuration("10x"));
        assertThrows(IllegalArgumentException.class, () -> TimeUtil.parseDuration(""));
    }

    @Test
    void formatsHumanReadableDurations() {
        assertEquals("0s", TimeUtil.formatDuration(0));
        assertEquals("45s", TimeUtil.formatDuration(45_000));
        assertEquals("1d 1h", TimeUtil.formatDuration(90_000_000));
        assertEquals("2m", TimeUtil.formatDuration(120_000));
    }
}
