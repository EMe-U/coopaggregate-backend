package com.coopaggregate.report;

import com.coopaggregate.payment.PaymentMethod;

/** RWF paid to members with one payment method. */
public record MethodAmount(PaymentMethod method, Long amountRwf) {
}
