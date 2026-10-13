package com.coopaggregate.delivery;

import java.math.BigDecimal;
import java.time.Instant;

import com.coopaggregate.grade.Grade;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.member.Member;

/**
 * deliveredAt is when the delivery happened (the device time for offline deliveries); syncedAt is when the
 * server saved it.
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
        Instant syncedAt) {

    public static DeliveryResponse from(Delivery delivery) {
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
                delivery.getCreatedAt());
    }
}
