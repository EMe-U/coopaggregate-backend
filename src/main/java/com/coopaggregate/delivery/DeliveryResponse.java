package com.coopaggregate.delivery;

import java.math.BigDecimal;
import java.time.Instant;

import com.coopaggregate.grade.Grade;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.member.Member;

/**
 * deliveredAt is when the delivery happened (the device time for offline deliveries); syncedAt is when the
 * server saved it. correctsId and correctedById link a corrected delivery and the one it replaced.
 */
public record DeliveryResponse(
        Long id,
        String receiptCode,
        Long memberId,
        String memberName,
        String memberNumber,
        Long gradeId,
        String gradeName,
        Long lotId,
        String lotCode,
        BigDecimal quantityKg,
        Long deductionRwf,
        Instant deliveredAt,
        Instant syncedAt,
        boolean reversed,
        String reversalReason,
        Long correctsId,
        Long correctedById,
        ConfirmationStatus confirmationStatus,
        ConfirmationMethod confirmationMethod,
        Instant confirmedAt,
        boolean pinMismatch,
        DisputeReason disputeReason,
        String disputeNote,
        Instant disputedAt,
        String resolutionNote) {

    public static DeliveryResponse from(Delivery delivery) {
        return from(delivery, null);
    }

    /** correctedById is the delivery that replaced this one, if it was corrected. */
    public static DeliveryResponse from(Delivery delivery, Long correctedById) {
        Member member = delivery.getMember();
        Lot lot = delivery.getLot();
        Grade grade = lot.getGrade();
        return new DeliveryResponse(
                delivery.getId(),
                delivery.getReceiptCode(),
                member.getId(),
                member.getFullName(),
                member.getMemberCode(),
                grade.getId(),
                grade.getName(),
                lot.getId(),
                lot.getLotCode(),
                delivery.getQuantityKg(),
                delivery.getDeductionAmount(),
                delivery.getDeliveredAt(),
                delivery.getCreatedAt(),
                delivery.isReversed(),
                delivery.getReversalReason(),
                delivery.getCorrects() == null ? null : delivery.getCorrects().getId(),
                correctedById,
                delivery.getConfirmationStatus(),
                delivery.getConfirmationMethod(),
                delivery.getConfirmedAt(),
                delivery.isPinMismatch(),
                delivery.getDisputeReason(),
                delivery.getDisputeNote(),
                delivery.getDisputedAt(),
                delivery.getResolutionNote());
    }
}
