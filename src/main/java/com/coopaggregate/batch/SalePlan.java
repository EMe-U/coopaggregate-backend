package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.util.List;

/**
 * Which batches a sale of one grade takes its kg from, and what each member is credited. When the sellable kg are
 * not enough, enough is false, lines is empty and nothing may be saved.
 */
public record SalePlan(
        BigDecimal quantityKg,
        long pricePerKg,
        long deductionPerKg,
        BigDecimal sellableKg,
        boolean enough,
        long totalAmountRwf,
        List<Line> lines,
        List<SellableBatch> skipped) {

    /** kg taken from one batch: gross is its part of the sale amount, the deduction is per kg taken. */
    public record Line(SellableBatch batch, BigDecimal kg, BigDecimal remainingAfterKg, long grossRwf,
                       long deductionRwf, long netRwf) {
    }

    public long totalDeductionRwf() {
        return lines.stream().mapToLong(Line::deductionRwf).sum();
    }

    public long totalNetRwf() {
        return lines.stream().mapToLong(Line::netRwf).sum();
    }

    /** waitingKg are the grade's kg in batches not confirmed yet, which cannot be sold. */
    public String shortageMessage(String gradeName, BigDecimal waitingKg) {
        String message = "Only " + sellableKg.toPlainString() + " kg of " + gradeName + " can be sold now, but "
                + quantityKg.toPlainString() + " kg were asked for.";
        if (!skipped.isEmpty()) {
            message += " Skipped batches are not counted.";
        }
        if (waitingKg.signum() > 0) {
            message += " " + waitingKg.toPlainString() + " kg are waiting for their members to confirm them.";
        }
        return message;
    }
}
