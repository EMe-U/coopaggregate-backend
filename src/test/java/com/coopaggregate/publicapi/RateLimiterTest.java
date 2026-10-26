package com.coopaggregate.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    /** A clock the test moves by hand. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-22T08:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void allowsUpToTheMaximumThenAsksToWait() {
        RateLimiter limiter = new RateLimiter(3, Duration.ofHours(1), new TestClock());

        assertEquals(0, limiter.tryAcquire("ip:1"));
        assertEquals(0, limiter.tryAcquire("ip:1"));
        assertEquals(0, limiter.tryAcquire("ip:1"));
        long wait = limiter.tryAcquire("ip:1");

        assertTrue(wait > 0 && wait <= 3601, "wait was " + wait);
    }

    @Test
    void refusedCallsDoNotExtendTheWait() {
        TestClock clock = new TestClock();
        RateLimiter limiter = new RateLimiter(1, Duration.ofMinutes(10), clock);
        limiter.tryAcquire("ip:1");
        for (int i = 0; i < 20; i++) {
            limiter.tryAcquire("ip:1");
        }

        clock.advance(Duration.ofMinutes(10).plusSeconds(1));

        assertEquals(0, limiter.tryAcquire("ip:1"));
    }

    @Test
    void windowSlidesSoOldCallsStopCounting() {
        TestClock clock = new TestClock();
        RateLimiter limiter = new RateLimiter(2, Duration.ofMinutes(10), clock);
        limiter.tryAcquire("ip:1");
        clock.advance(Duration.ofMinutes(6));
        limiter.tryAcquire("ip:1");
        assertTrue(limiter.tryAcquire("ip:1") > 0);

        clock.advance(Duration.ofMinutes(5));

        assertEquals(0, limiter.tryAcquire("ip:1"));
        assertTrue(limiter.tryAcquire("ip:1") > 0);
    }

    @Test
    void everyKeyHasItsOwnCount() {
        RateLimiter limiter = new RateLimiter(1, Duration.ofHours(1), new TestClock());

        assertEquals(0, limiter.tryAcquire("ip:1"));
        assertEquals(0, limiter.tryAcquire("ip:2"));
        assertTrue(limiter.tryAcquire("ip:1") > 0);
    }
}
