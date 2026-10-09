package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.util.List;

public record StockResponse(BigDecimal totalRemainingKg, long openLots, long closedLots, List<GradeStock> byGrade) {

    public record GradeStock(String gradeName, BigDecimal remainingKg) {
    }
}
