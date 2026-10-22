package com.coopaggregate.publicapi;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allows at most `max` calls per key in a sliding window. Kept in memory, so counters reset when the app restarts,
 * which is fine for a single API instance. Keys (an IP address or a phone number) are never logged.
 */
public class RateLimiter {

    // Above this many keys, keys with no recent calls are dropped so the map cannot grow without limit.
    private static final int PRUNE_ABOVE = 10_000;

    private final int max;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> calls = new ConcurrentHashMap<>();

    public RateLimiter(int max, Duration window) {
        this(max, window, Clock.systemUTC());
    }

    RateLimiter(int max, Duration window, Clock clock) {
        this.max = max;
        this.window = window;
        this.clock = clock;
    }

    /** Counts the call and returns 0 if it is allowed, otherwise the seconds to wait before trying again. */
    public long tryAcquire(String key) {
        Instant now = clock.instant();
        Instant since = now.minus(window);
        if (calls.size() > PRUNE_ABOVE) {
            calls.values().removeIf(times -> times.isEmpty() || times.peekLast().isBefore(since));
        }
        long[] wait = {0};
        calls.compute(key, (k, times) -> {
            Deque<Instant> recent = times == null ? new ArrayDeque<>() : times;
            while (!recent.isEmpty() && recent.peekFirst().isBefore(since)) {
                recent.pollFirst();
            }
            if (recent.size() >= max) {
                wait[0] = Math.max(1, Duration.between(now, recent.peekFirst().plus(window)).toSeconds() + 1);
            } else {
                recent.addLast(now);
            }
            return recent;
        });
        return wait[0];
    }
}
