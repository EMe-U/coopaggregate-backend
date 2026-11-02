package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.time.Instant;

import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.lot.LotPortion;

/**
 * A portion a sale may take kg from: the kg of one confirmed, not reversed delivery in one lot, with kg left.
 * fixedLot is false for the lots from before fixed-size lots, which are sold first, oldest delivery first.
 */
public record SellableBatch(
        Long portionId,
        Long deliveryId,
        String receiptCode,
        Long lotId,
        String lotCode,
        boolean fixedLot,
        Long memberId,
        String memberNumber,
        String memberName,
        Instant deliveredAt,
        BigDecimal remainingKg) {

    public static SellableBatch from(LotPortion portion) {
        Delivery delivery = portion.getDelivery();
        return new SellableBatch(portion.getId(), delivery.getId(), delivery.getReceiptCode(),
                portion.getLot().getId(), portion.getLot().getLotCode(), portion.getLot().getCapacityKg() != null,
                delivery.getMember().getId(), delivery.getMember().getMemberCode(), delivery.getMember().getFullName(),
                delivery.getDeliveredAt(), portion.unallocatedKg());
    }
}
