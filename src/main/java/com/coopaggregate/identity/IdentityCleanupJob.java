package com.coopaggregate.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clears the PKCE verifiers and verified details that are no longer needed (see IdentityVerificationService). Runs every
 * minute, so verified details never stay more than about 31 minutes.
 */
@Component
public class IdentityCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(IdentityCleanupJob.class);

    private final IdentityVerificationService verificationService;

    public IdentityCleanupJob(IdentityVerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @Scheduled(fixedDelayString = "${app.identity.cleanup-delay-ms:60000}", initialDelay = 60_000)
    public void run() {
        try {
            int cleaned = verificationService.clearExpired();
            if (cleaned > 0) {
                log.info("Cleared the details of {} identity verifications that were not used in time", cleaned);
            }
        } catch (RuntimeException e) {
            log.warn("Clearing old identity verifications failed: {}", e.getClass().getSimpleName());
        }
    }
}
