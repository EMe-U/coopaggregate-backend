package com.coopaggregate.dispute;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DisputeReason;

/**
 * One disputed delivery. daysOpen counts whole days from disputedAt until it was settled, or until now while it is
 * open. outcome, settledAt, resolutionNote and correctedById are only set once it is settled.
 */
public record DisputeItem(
        Long deliveryId,
        String receiptCode,
        Long memberId,
        String memberName,
        String memberNumber,
        String gradeName,
        String lotCode,
        BigDecimal quantityKg,
        DisputeReason reason,
        String note,
        Instant disputedAt,
        long daysOpen,
        boolean open,
        DisputeOutcome outcome,
        Instant settledAt,
        String resolutionNote,
        Long correctedById) {

    /** correctedById is the delivery that replaced this one, when it was corrected. */
    static DisputeItem from(Delivery delivery, Long correctedById, Instant now) {
        DisputeOutcome outcome = outcomeOf(delivery, correctedById);
        Instant settledAt = outcome == null ? null
                : outcome == DisputeOutcome.RESOLVED ? delivery.getConfirmedAt() : delivery.getReversedAt();
        Instant end = settledAt != null ? settledAt : now;
        return new DisputeItem(
                delivery.getId(),
                delivery.getReceiptCode(),
                delivery.getMember().getId(),
                delivery.getMember().getFullName(),
                delivery.getMember().getMemberCode(),
                delivery.getLot().getGrade().getName(),
                delivery.getLot().getLotCode(),
                delivery.getQuantityKg(),
                delivery.getDisputeReason(),
                delivery.getDisputeNote(),
                delivery.getDisputedAt(),
                Math.max(0, Duration.between(delivery.getDisputedAt(), end).toDays()),
                outcome == null,
                outcome,
                settledAt,
                outcome == null ? null : delivery.getResolutionNote(),
                correctedById);
    }

    // Open while the delivery is still DISPUTED and not reversed, the same rule as the dashboard count.
    private static DisputeOutcome outcomeOf(Delivery delivery, Long correctedById) {
        if (delivery.isReversed()) {
            return correctedById != null ? DisputeOutcome.CORRECTED : DisputeOutcome.REVERSED;
        }
        return delivery.getConfirmationStatus() == ConfirmationStatus.DISPUTED
                ? null : DisputeOutcome.RESOLVED;
    }
}
