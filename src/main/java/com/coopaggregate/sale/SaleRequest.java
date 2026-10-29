package com.coopaggregate.sale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * A sale of one grade. The kg are taken from the oldest confirmed batches of the grade, whatever their lot. To pass
 * over some of them, list their delivery ids in skipDeliveryIds and give a reason of 10 to 500 characters.
 */
public record SaleRequest(
        @NotNull(message = "is required")
        UUID clientUuid,

        @NotNull(message = "is required")
        Long gradeId,

        @NotNull(message = "is required")
        Long buyerId,

        @NotNull(message = "is required")
        @Positive(message = "must be more than 0")
        @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimals")
        BigDecimal quantityKg,

        @NotNull(message = "is required")
        @Positive(message = "must be more than 0")
        Long pricePerKg,

        @PastOrPresent(message = "cannot be in the future")
        LocalDate soldAt,

        @Size(max = 50, message = "can have at most 50 batches")
        List<@NotNull(message = "must not be empty") Long> skipDeliveryIds,

        @Size(max = 500, message = "must be at most 500 characters")
        String skipReason) {
}
