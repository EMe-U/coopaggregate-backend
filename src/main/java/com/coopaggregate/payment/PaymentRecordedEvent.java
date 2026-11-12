package com.coopaggregate.payment;

/** A new payment to a member was saved. Published inside the transaction; listeners act after the commit. */
public record PaymentRecordedEvent(Long paymentId) {
}
