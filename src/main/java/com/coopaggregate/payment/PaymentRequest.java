package com.coopaggregate.payment;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PaymentRequest(
        @NotNull(message = "is required")
        UUID clientUuid,

        @NotNull(message = "is required")
        Long memberId,

        @NotNull(message = "is required")
        @Positive(message = "must be more than 0")
        Long amountRwf,

        @NotNull(message = "is required (CASH or MOMO)")
        PaymentMethod method,

        @Size(max = 50, message = "must be at most 50 characters")
        String reference,

        @PastOrPresent(message = "cannot be in the future")
        LocalDate paidAt) {
}
