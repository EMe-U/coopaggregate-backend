package com.coopaggregate.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

// Slows down password guessing (NFR4). Kept in memory, so counters reset on restart,
// which is fine for a single API instance.
@Component
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    private final Map<String, FailedLogins> failuresByEmail = new ConcurrentHashMap<>();

    public boolean isLocked(String email) {
        FailedLogins failures = failuresByEmail.get(key(email));
        if (failures == null || failures.lockedUntil() == null) {
            return false;
        }
        if (Instant.now().isBefore(failures.lockedUntil())) {
            return true;
        }
        failuresByEmail.remove(key(email));
        return false;
    }

    public void recordFailure(String email) {
        failuresByEmail.compute(key(email), (key, previous) -> {
            int count = previous == null ? 1 : previous.count() + 1;
            Instant lockedUntil = count >= MAX_FAILURES ? Instant.now().plus(LOCK_DURATION) : null;
            return new FailedLogins(count, lockedUntil);
        });
    }

    public void recordSuccess(String email) {
        failuresByEmail.remove(key(email));
    }

    private String key(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private record FailedLogins(int count, Instant lockedUntil) {
    }
}
