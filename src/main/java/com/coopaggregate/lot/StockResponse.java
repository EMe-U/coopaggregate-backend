package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.util.List;

public record StockResponse(
        BigDecimal totalRemainingKg,
        BigDecimal totalDeliveredKg,
        BigDecimal totalSoldKg,
        BigDecimal totalLostKg,
        // Delivered kg that members have not confirmed yet. Included in the totals above (physical stock).
        BigDecimal pendingConfirmationKg,
        BigDecimal addedTodayKg,
        long deliveriesToday,
        long openLots,
        long closedLots,
        List<GradeStock> byGrade) {

    public record GradeStock(String gradeName, BigDecimal remainingKg) {
    }
}
