package io.craftrealm.core.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    @Test
    void bucketIsDrainedThenRefuses() {
        RateLimiter limiter = new RateLimiter(1, 2);
        assertTrue(limiter.tryAcquire("steve"));
        assertTrue(limiter.tryAcquire("steve"));
        assertFalse(limiter.tryAcquire("steve"));
    }

    @Test
    void keysAreIndependent() {
        RateLimiter limiter = new RateLimiter(1, 1);
        assertTrue(limiter.tryAcquire("a"));
        assertFalse(limiter.tryAcquire("a"));
        assertTrue(limiter.tryAcquire("b"));
    }

    @Test
    void forgettingRestoresTheBucket() {
        RateLimiter limiter = new RateLimiter(1, 1);
        assertTrue(limiter.tryAcquire("a"));
        limiter.forget("a");
        assertTrue(limiter.tryAcquire("a"));
    }
}
