package com.coopaggregate.delivery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DeliveryRequest(
        @NotNull(message = "is required")
        UUID clientUuid,

        @NotNull(message = "is required")
        Long memberId,

        @NotNull(message = "is required")
        Long gradeId,

        @NotNull(message = "is required")
        @Positive(message = "must be more than 0")
        @DecimalMax(value = "5000", message = "must be at most 5000 kg")
        @Digits(integer = 4, fraction = 2, message = "must have at most 2 decimals")
        BigDecimal quantityKg,

        Instant deliveredAt) {
}
