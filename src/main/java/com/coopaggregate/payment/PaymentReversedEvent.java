package com.coopaggregate.payment;

/** A payment to a member was reversed. Published inside the transaction; listeners act after the commit. */
public record PaymentReversedEvent(Long paymentId) {
}
