package com.coopaggregate.buyer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** The new status and, optionally, a message the buyer will see when they look up their request. */
public record BuyerRequestStatusUpdate(
        @NotNull(message = "is required (PENDING, ACCEPTED or REJECTED)")
        BuyerRequestStatus status,

        @Size(max = 500, message = "must be at most 500 characters")
        String publicMessage) {
}
