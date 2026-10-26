package com.coopaggregate.report;

import java.math.BigDecimal;

import com.coopaggregate.loss.LossReason;

/** Kilograms lost for one reason. */
public record ReasonKg(LossReason reason, BigDecimal kg) {
}
