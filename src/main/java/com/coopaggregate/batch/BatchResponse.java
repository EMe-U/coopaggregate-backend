package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.time.Instant;

import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.lot.LotStatus;

/** One batch: a delivery with the kg sold and lost from it so far and what is left. */
public record BatchResponse(
        Long deliveryId,
        String receiptCode,
        Long memberId,
        String memberNumber,
        String memberName,
        String gradeName,
        Long lotId,
        String lotCode,
        LotStatus lotStatus,
        Instant deliveredAt,
        BigDecimal receivedKg,
        BigDecimal soldKg,
        BigDecimal lostKg,
        BigDecimal remainingKg,
        ConfirmationStatus confirmationStatus) {

    static BatchResponse from(Delivery delivery) {
        return new BatchResponse(delivery.getId(), delivery.getReceiptCode(), delivery.getMember().getId(),
                delivery.getMember().getMemberCode(), delivery.getMember().getFullName(),
                delivery.getLot().getGrade().getName(), delivery.getLot().getId(), delivery.getLot().getLotCode(),
                delivery.getLot().getStatus(), delivery.getDeliveredAt(), delivery.getQuantityKg(),
                delivery.getSoldKg(), delivery.getLostKg(), delivery.getRemainingKg(),
                delivery.getConfirmationStatus());
    }
}
