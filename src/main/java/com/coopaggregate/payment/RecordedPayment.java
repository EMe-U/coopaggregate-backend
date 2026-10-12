package com.coopaggregate.payment;

/** created is false when the payment had already been recorded with the same client UUID. */
public record RecordedPayment(PaymentResponse payment, boolean created) {
}
