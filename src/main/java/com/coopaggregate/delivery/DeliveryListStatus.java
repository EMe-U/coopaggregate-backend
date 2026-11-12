package com.coopaggregate.delivery;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import com.coopaggregate.error.BadRequestException;

/**
 * The status filter of the delivery list. PENDING, CONFIRMED and DISPUTED are the confirmation status of a delivery
 * that is not reversed; REVERSED is any reversed delivery, including the original of a correction.
 */
public enum DeliveryListStatus {
    PENDING,
    CONFIRMED,
    DISPUTED,
    REVERSED;

    /** null when no status is given; an unknown value is a 400 that names the allowed ones. */
    public static DeliveryListStatus parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Unknown delivery status '" + value.trim() + "'. Use one of "
                    + Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", ")) + ".");
        }
    }

    ConfirmationStatus confirmationStatus() {
        return this == REVERSED ? null : ConfirmationStatus.valueOf(name());
    }
}
