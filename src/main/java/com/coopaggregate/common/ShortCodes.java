package com.coopaggregate.common;

import java.security.SecureRandom;
import java.util.function.Predicate;

/** Short random codes such as RCT-7K2Q, easy to read on a phone or say aloud. */
public final class ShortCodes {

    // No 0/O or 1/I/L, so a code read aloud or from a small screen is not misread.
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int LENGTH = 4;
    private static final int ATTEMPTS = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ShortCodes() {
    }

    public static String unique(String prefix, Predicate<String> isTaken) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            StringBuilder code = new StringBuilder(prefix);
            for (int i = 0; i < LENGTH; i++) {
                code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            if (!isTaken.test(code.toString())) {
                return code.toString();
            }
        }
        throw new IllegalStateException("Could not create a unique " + prefix + " code. Please try again.");
    }
}
