package com.coopaggregate.delivery;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** Creates and checks the one-time codes members give the manager to confirm a delivery. */
@Component
public class ConfirmationCodes {

    public static final Duration VALIDITY = Duration.ofMinutes(10);
    public static final int MAX_WRONG_TRIES = 3;

    private final SecureRandom random = new SecureRandom();
    private final PasswordEncoder passwordEncoder;

    public ConfirmationCodes(PasswordEncoder passwordEncoder) {
        this.passwordEncoder = passwordEncoder;
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
}
