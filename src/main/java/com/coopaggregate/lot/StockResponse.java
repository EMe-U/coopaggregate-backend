package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.util.List;

/**
 * Stock is read per grade from the batches (deliveries), whatever their lot. totalRemainingKg is the kg left in all
 * batches that are not reversed (physical stock); it is the sum of three parts: sellable (confirmed, can be sold),
 * waiting (pending the member's confirmation) and disputed. Only sellable kg can be sold.
 */
public record StockResponse(
        BigDecimal totalRemainingKg,
        BigDecimal totalSellableKg,
        BigDecimal totalWaitingKg,
        BigDecimal totalDisputedKg,
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

    /** remainingKg = sellableKg + waitingKg + disputedKg. A grade with no kg left is not listed. */
    public record GradeStock(String gradeName, BigDecimal remainingKg, BigDecimal sellableKg, BigDecimal waitingKg,
                             BigDecimal disputedKg) {
    }
}
