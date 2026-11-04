package com.coopaggregate.delivery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates and checks the one-time codes members give the manager to confirm a delivery. */
@Component
public class ConfirmationCodes {

    public static final Duration VALIDITY = Duration.ofMinutes(10);
    public static final int MAX_WRONG_TRIES = 3;
    public static final int MAX_RESENDS = 3;

    private static final Logger log = LoggerFactory.getLogger(ConfirmationCodes.class);

    private final SecureRandom random = new SecureRandom();
    private final PasswordEncoder passwordEncoder;
    // TEMPORARY, for the demo only: remove with SMS_DEMO_CODE after it. Null when not set.
    private final String demoCode;

    public ConfirmationCodes(PasswordEncoder passwordEncoder) {
        this(passwordEncoder, "");
    }

    @Autowired
    public ConfirmationCodes(PasswordEncoder passwordEncoder, @Value("${app.sms.demo-code:}") String demoCode) {
        this.passwordEncoder = passwordEncoder;
        if (demoCode == null || demoCode.isBlank()) {
            this.demoCode = null;
            return;
        }
        String trimmed = demoCode.trim();
        if (!trimmed.matches("\\d{6}")) {
            // Never repeat the value, even when it is wrong.
            throw new IllegalStateException("SMS_DEMO_CODE must be exactly 6 digits, or not set.");
        }
        this.demoCode = trimmed;
        log.warn("A demo confirmation code is enabled. Remove SMS_DEMO_CODE after the demo.");
    }

    /** Gives the delivery a new 6-digit code, valid for 10 minutes, and returns it so it can be sent. */
    public String issue(Delivery delivery, Instant now) {
        String code = String.format("%06d", random.nextInt(1_000_000));
        delivery.issueCode(passwordEncoder.encode(code), now.plus(VALIDITY));
        return code;
    }

    public boolean matches(Delivery delivery, String code) {
        return delivery.getConfirmationCodeHash() != null
                && passwordEncoder.matches(code, delivery.getConfirmationCodeHash());
    }

    /** TEMPORARY, for the demo only: true when SMS_DEMO_CODE is set and the code is that one. */
    public boolean isDemoCode(String code) {
        return demoCode != null && code != null
                && MessageDigest.isEqual(demoCode.getBytes(StandardCharsets.UTF_8),
                        code.getBytes(StandardCharsets.UTF_8));
    }
}
