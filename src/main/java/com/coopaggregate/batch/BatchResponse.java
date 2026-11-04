package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.time.Instant;

import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.lot.LotStatus;

/**
 * One batch: a delivery with the kg sold and lost from it so far and what is left. The money is what the member
 * was credited by batch for the kg sold; kg paid through a lot's share are not counted per batch (see payment).
 */
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
        ConfirmationStatus confirmationStatus,
        long grossRwf,
        long deductionRwf,
        long earnedRwf,
        BatchPayment payment) {

    /** credit is null when nothing of the batch was credited; lotShared is whether its member has a share of the lot. */
    static BatchResponse from(Delivery delivery, BatchCredit credit, boolean lotShared) {
        BigDecimal creditedKg = credit == null ? BigDecimal.ZERO : credit.creditedKg();
        return new BatchResponse(delivery.getId(), delivery.getReceiptCode(), delivery.getMember().getId(),
                delivery.getMember().getMemberCode(), delivery.getMember().getFullName(),
                delivery.getLot().getGrade().getName(), delivery.getLot().getId(), delivery.getLot().getLotCode(),
                delivery.getLot().getStatus(), delivery.getDeliveredAt(), delivery.getQuantityKg(),
                delivery.getSoldKg(), delivery.getLostKg(), delivery.getRemainingKg(),
                delivery.getConfirmationStatus(),
                credit == null ? 0 : credit.grossRwf(),
                credit == null ? 0 : credit.deductionRwf(),
                credit == null ? 0 : credit.earnedRwf(),
                paymentOf(delivery.getSoldKg(), creditedKg, lotShared));
    }

    static BatchPayment paymentOf(BigDecimal soldKg, BigDecimal creditedKg, boolean lotShared) {
        if (soldKg.signum() == 0) {
            return BatchPayment.NOTHING_SOLD;
        }
        if (creditedKg.compareTo(soldKg) >= 0) {
            return BatchPayment.BATCH;
        }
        return lotShared ? BatchPayment.LOT_SHARE : BatchPayment.AWAITING_SETTLEMENT;
    }
}
