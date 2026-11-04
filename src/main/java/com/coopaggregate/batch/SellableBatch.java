package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.time.Instant;

import com.coopaggregate.delivery.Delivery;

/** A batch a sale may take kg from: confirmed, not reversed and with kg left. */
public record SellableBatch(
        Long deliveryId,
        String receiptCode,
        Long lotId,
        String lotCode,
        Long memberId,
        String memberNumber,
        String memberName,
        Instant deliveredAt,
        BigDecimal remainingKg) {

    public static SellableBatch from(Delivery batch) {
        return new SellableBatch(batch.getId(), batch.getReceiptCode(), batch.getLot().getId(),
                batch.getLot().getLotCode(), batch.getMember().getId(), batch.getMember().getMemberCode(),
                batch.getMember().getFullName(), batch.getDeliveredAt(), batch.getRemainingKg());
    }
}
