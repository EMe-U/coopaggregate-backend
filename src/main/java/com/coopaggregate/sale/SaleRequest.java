package com.coopaggregate.sale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.coopaggregate.payment.PaymentMethod;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * A sale of one grade. The kg are taken from the lots of the grade first in, first out: lots from before fixed-size
 * lots first, then the fixed-size lots in the order they were filled. Nothing can be skipped: skipLotIds, skipReason
 * and skipDeliveryIds are kept only so that a request still asking to skip (a non-empty list or reason) is refused
 * with a 400 instead of being sold in an order it did not expect. Empty or null, they ask for nothing.
 * The buyer pays in full: paymentMethod says how, and paymentReference is required for MOMO and BANK. The sale is
 * saved as an order of its own, which keeps them.
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

        // Refused when not empty; see the class comment.
        List<Long> skipLotIds,

        // Refused when not empty; see the class comment.
        String skipReason,

        // Refused when not empty; see the class comment.
        List<Long> skipDeliveryIds,

        @NotNull(message = "is required (CASH, MOMO or BANK)")
        PaymentMethod paymentMethod,

        // Required for MOMO and BANK; checked in the service because it depends on the method.
        @Size(max = 50, message = "must be at most 50 characters")
        String paymentReference) {
}
