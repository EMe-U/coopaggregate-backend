package com.coopaggregate.loss;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record LossRequest(
        @NotNull(message = "is required")
        @Positive(message = "must be more than 0")
        @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimals")
        BigDecimal quantityKg,

        @NotNull(message = "is required (ROT, DAMAGE, THEFT or OTHER)")
        LossReason reason,

        @PastOrPresent(message = "cannot be in the future")
        LocalDate recordedAt,

        @Size(max = 500, message = "must be at most 500 characters")
        String notes) {
}
