package com.coopaggregate.sale;

import java.math.BigDecimal;

public record SaleSummaryResponse(
        long salesAmountThisMonth,
        BigDecimal soldKgThisMonth,
        long buyersThisMonth,
        BigDecimal averagePricePerKg) {
}
