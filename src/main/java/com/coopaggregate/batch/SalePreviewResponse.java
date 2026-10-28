package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The batches a sale of a grade would take its kg from, oldest first, and what each member would be credited.
 * Nothing is saved. When enough is false, message says why and batches is empty. The sale itself works this out
 * again when it is recorded, so its batches can differ if something changed in between.
 */
public record SalePreviewResponse(
        Long gradeId,
        String gradeName,
        BigDecimal quantityKg,
        long pricePerKg,
        boolean belowFloorPrice,
        long deductionPerKg,
        BigDecimal sellableKg,
        BigDecimal waitingConfirmationKg,
        BigDecimal disputedKg,
        boolean enough,
        String message,
        long totalAmountRwf,
        long totalDeductionRwf,
        long totalNetRwf,
        List<Line> batches,
        List<Skipped> skipped) {

    public record Line(
            Long deliveryId,
            String receiptCode,
            Long lotId,
            String lotCode,
            Long memberId,
            String memberNumber,
            String memberName,
            Instant deliveredAt,
            BigDecimal takeKg,
            BigDecimal remainingAfterKg,
            long grossRwf,
            long deductionRwf,
            long netRwf) {
    }

    public record Skipped(Long deliveryId, String receiptCode, String lotCode, String memberName,
                          Instant deliveredAt, BigDecimal remainingKg) {
    }
}
