package com.coopaggregate.delivery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The manager's note on how a delivery was checked and found correct. */
public record ManagerReviewRequest(
        @NotBlank(message = "is required: say how the delivery was checked")
        @Size(max = 500, message = "must be at most 500 characters")
        String note) {
}
