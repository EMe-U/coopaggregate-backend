package com.coopaggregate.share;

import java.math.BigDecimal;

import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;

/** A delivery that keeps its lot from being shared, because the member has not confirmed it. */
public record UnconfirmedDelivery(
        Long id,
        String receiptCode,
        Long memberId,
        String memberName,
        String memberNumber,
        BigDecimal quantityKg,
        ConfirmationStatus status) {

    static UnconfirmedDelivery from(Delivery delivery) {
        return new UnconfirmedDelivery(delivery.getId(), delivery.getReceiptCode(), delivery.getMember().getId(),
                delivery.getMember().getFullName(), delivery.getMember().getMemberCode(), delivery.getQuantityKg(),
                delivery.getConfirmationStatus());
    }
}
