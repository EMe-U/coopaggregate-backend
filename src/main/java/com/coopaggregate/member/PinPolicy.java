package com.coopaggregate.member;

import com.coopaggregate.error.BadRequestException;

/** Rules for a member's delivery PIN. */
public final class PinPolicy {

    private PinPolicy() {
    }

    public static void check(String pin) {
        if (pin == null || !pin.matches("\\d{4}")) {
            throw new BadRequestException("The PIN must be exactly 4 digits.");
        }
        if (isTrivial(pin)) {
            throw new BadRequestException(
                    "This PIN is too easy to guess. Do not use 4 equal digits or digits in a row like 1234 or 4321.");
        }
    }

    // All digits the same (0000), or each digit one more (1234) or one less (4321) than the one before.
    static boolean isTrivial(String pin) {
        boolean same = true;
        boolean ascending = true;
        boolean descending = true;
        for (int i = 1; i < pin.length(); i++) {
            int step = pin.charAt(i) - pin.charAt(i - 1);
            same &= step == 0;
            ascending &= step == 1;
            descending &= step == -1;
        }
        return same || ascending || descending;
    }
}
