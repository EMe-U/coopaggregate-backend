package com.coopaggregate.delivery;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DisputeRequest(
        @NotNull(message = "is required (WRONG_KG, WRONG_GRADE, NOT_MY_DELIVERY or OTHER)")
        DisputeReason reason,

        @Size(max = 500, message = "must be at most 500 characters")
        String note) {
}
