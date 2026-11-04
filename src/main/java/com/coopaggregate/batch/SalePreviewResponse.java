package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The portions a sale of a grade would take its kg from, in the order it takes them (older lots first, then the
 * fixed-size lots in fill order, each shared across its portions), and what each member would be credited. Nothing is
 * saved. When enough is false, message says why and batches is empty. The sale itself works this out again when it is
 * recorded, so its lines can differ if something changed in between. A delivery split across two lots can appear on
 * two lines, one per portion. skipped lists the lots passed over.
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
            Long lotPortionId,
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

    public record Skipped(Long lotId, String lotCode, BigDecimal sellableKg) {
    }
}
