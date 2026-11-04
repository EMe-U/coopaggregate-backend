package com.coopaggregate.dispute;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DisputeReason;
import com.coopaggregate.lot.PortionResponse;

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
        // Its own lot (from before fixed-size lots) or its first lot; null when it is in no lot. lots has them all.
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
        Long correctedById,
        List<PortionResponse> lots) {

    /** correctedById is the delivery that replaced this one, when it was corrected. */
    static DisputeItem from(Delivery delivery, Long correctedById, Instant now, List<PortionResponse> lots) {
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
                delivery.getGrade().getName(),
                delivery.getLot() != null ? delivery.ownLotCode() : lots.isEmpty() ? null : lots.get(0).lotCode(),
                delivery.getQuantityKg(),
                delivery.getDisputeReason(),
                delivery.getDisputeNote(),
                delivery.getDisputedAt(),
                Math.max(0, Duration.between(delivery.getDisputedAt(), end).toDays()),
                outcome == null,
                outcome,
                settledAt,
                outcome == null ? null : delivery.getResolutionNote(),
                correctedById,
                lots);
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
