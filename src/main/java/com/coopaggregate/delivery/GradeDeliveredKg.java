package com.coopaggregate.delivery;

import java.math.BigDecimal;

/** Kilograms delivered for one grade in a period. */
public record GradeDeliveredKg(String gradeName, BigDecimal kg) {
}
