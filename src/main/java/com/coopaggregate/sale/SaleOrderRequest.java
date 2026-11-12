package com.coopaggregate.sale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;

public record SaleOrderRequest(
        @NotNull(message = "is required")
        UUID clientUuid,

        @NotNull(message = "is required")
        Long buyerId,

        @PastOrPresent(message = "cannot be in the future")
        LocalDate soldAt,

        @NotEmpty(message = "must have at least one item")
        List<@Valid Item> items) {

    /** One grade of the order. Each grade can appear once; the skip fields are refused as in a sale. */
    public record Item(
            @NotNull(message = "is required")
            Long gradeId,

            @NotNull(message = "is required")
            @Positive(message = "must be more than 0")
            @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimals")
            BigDecimal quantityKg,

            @NotNull(message = "is required")
            @Positive(message = "must be more than 0")
            Long pricePerKg,

            // Refused when not empty: lots cannot be skipped.
            List<Long> skipLotIds,

            // Refused when not empty: lots cannot be skipped.
            String skipReason,

            // Refused when not empty: lots cannot be skipped.
            List<Long> skipDeliveryIds) {
    }
}
