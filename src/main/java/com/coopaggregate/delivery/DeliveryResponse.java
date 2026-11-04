package com.coopaggregate.delivery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.coopaggregate.grade.Grade;
import com.coopaggregate.lot.PortionResponse;
import com.coopaggregate.member.Member;

/**
 * deliveredAt is when the delivery happened (the device time for offline deliveries); syncedAt is when the
 * server saved it. correctsId and correctedById link a corrected delivery and the one it replaced. lots lists the
 * lots the delivery is in (one portion each, oldest lot first): empty while it waits for confirmation, two or more when
 * it filled the end of a lot and started the next. lotId and lotCode are the delivery's own lot (from before fixed-size
 * lots) or its first lot, kept for screens that show one lot.
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
        String confirmationLabel,
        int codeResendsLeft,
        Instant confirmedAt,
        DisputeReason disputeReason,
        String disputeNote,
        Instant disputedAt,
        String resolutionNote,
        List<PortionResponse> lots) {

    /** A delivery just recorded: in no lot yet. */
    public static DeliveryResponse from(Delivery delivery) {
        return from(delivery, null, List.of());
    }

    /** correctedById is the delivery that replaced this one, if it was corrected; lots are its portions. */
    public static DeliveryResponse from(Delivery delivery, Long correctedById, List<PortionResponse> lots) {
        Member member = delivery.getMember();
        Grade grade = delivery.getGrade();
        PortionResponse first = lots.isEmpty() ? null : lots.get(0);
        return new DeliveryResponse(
                delivery.getId(),
                delivery.getReceiptCode(),
                member.getId(),
                member.getFullName(),
                member.getMemberCode(),
                grade.getId(),
                grade.getName(),
                delivery.getLot() != null ? delivery.ownLotId() : first == null ? null : first.lotId(),
                delivery.getLot() != null ? delivery.ownLotCode() : first == null ? null : first.lotCode(),
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
                delivery.getConfirmationMethod() == null ? null : delivery.getConfirmationMethod().label(),
                ConfirmationCodes.MAX_RESENDS - delivery.getConfirmationCodeResends(),
                delivery.getConfirmedAt(),
                delivery.getDisputeReason(),
                delivery.getDisputeNote(),
                delivery.getDisputedAt(),
                delivery.getResolutionNote(),
                lots);
    }
}
