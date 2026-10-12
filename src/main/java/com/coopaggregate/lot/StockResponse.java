package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.util.List;

public record StockResponse(
        BigDecimal totalRemainingKg,
        BigDecimal totalDeliveredKg,
        BigDecimal totalSoldKg,
        BigDecimal totalLostKg,
        BigDecimal addedTodayKg,
        long deliveriesToday,
        long openLots,
        long closedLots,
        List<GradeStock> byGrade) {

    public record GradeStock(String gradeName, BigDecimal remainingKg) {
    }
}
