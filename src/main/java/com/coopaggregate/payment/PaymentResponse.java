package com.coopaggregate.payment;

import java.time.Instant;
import java.time.LocalDate;

import com.coopaggregate.member.Member;

public record PaymentResponse(
        Long id,
        Long memberId,
        String memberName,
        String memberNumber,
        long amountRwf,
        PaymentMethod method,
        String reference,
        LocalDate paidAt,
        Instant createdAt) {

    public static PaymentResponse from(ProducePayment payment) {
        Member member = payment.getMember();
        return new PaymentResponse(
                payment.getId(),
                member.getId(),
                member.getFullName(),
                member.getMemberCode(),
                payment.getAmount(),
                payment.getMethod(),
                payment.getReferenceNo(),
                payment.getPaymentDate(),
                payment.getCreatedAt());
    }
}
