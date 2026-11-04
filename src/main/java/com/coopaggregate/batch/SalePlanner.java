package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.share.ShareCalculator;

/**
 * Plans a sale of one grade: kg are taken from the oldest sellable batches first, whatever lot they are in, and
 * each batch's member is credited for the kg taken. Plain code without the database: the caller passes the
 * sellable batches in FIFO order (by delivered time, then id).
 */
public final class SalePlanner {

    private SalePlanner() {
    }

    /**
     * skipIds are batches the manager chose not to use. Each must be a sellable batch that the sale would otherwise
     * have reached; the reason for skipping is the caller's to check and record.
     */
    public static SalePlan plan(List<SellableBatch> sellableOldestFirst, BigDecimal quantityKg, long pricePerKg,
                                long deductionPerKg, Collection<Long> skipIds) {
        if (pricePerKg <= deductionPerKg) {
            throw new BadRequestException("The price must be more than the deduction of " + deductionPerKg
                    + " RWF per kg, otherwise the members would earn nothing.");
        }
        Set<Long> skip = Set.copyOf(skipIds);
        List<SellableBatch> skipped = sellableOldestFirst.stream()
                .filter(batch -> skip.contains(batch.deliveryId()))
                .toList();
        if (skipped.size() != skip.size()) {
            throw new BadRequestException("Only sellable batches of this grade can be skipped: confirmed, not "
                    + "reversed and with kg left.");
        }

        List<SellableBatch> used = new ArrayList<>();
        List<BigDecimal> kgs = new ArrayList<>();
        BigDecimal sellableKg = BigDecimal.ZERO;
        BigDecimal left = quantityKg;
        int lastUsed = -1;
        for (int i = 0; i < sellableOldestFirst.size(); i++) {
            SellableBatch batch = sellableOldestFirst.get(i);
            if (skip.contains(batch.deliveryId())) {
                continue;
            }
            sellableKg = sellableKg.add(batch.remainingKg());
            if (left.signum() > 0) {
                BigDecimal kg = batch.remainingKg().min(left);
                used.add(batch);
                kgs.add(kg);
                left = left.subtract(kg);
                lastUsed = i;
            }
        }

        if (left.signum() > 0) {
            return new SalePlan(quantityKg, pricePerKg, deductionPerKg, sellableKg, false, 0, List.of(), skipped);
        }
        // Skipping is for passing over a batch the sale would have used; anything newer is not reached anyway.
        for (SellableBatch batch : skipped) {
            if (sellableOldestFirst.indexOf(batch) > lastUsed) {
                throw new BadRequestException("Batch " + batch.receiptCode() + " would not be used by this sale, "
                        + "so it does not need to be skipped.");
            }
        }

        long totalAmount = totalAmount(quantityKg, pricePerKg);
        List<Long> gross = ShareCalculator.split(totalAmount, kgs);
        List<SalePlan.Line> lines = new ArrayList<>();
        for (int i = 0; i < used.size(); i++) {
            BigDecimal kg = kgs.get(i);
            // Rounding can only make the deduction bigger than the gross for a few grams; the member never owes.
            long deduction = Math.min(deduction(kg, deductionPerKg), gross.get(i));
            lines.add(new SalePlan.Line(used.get(i), kg, used.get(i).remainingKg().subtract(kg), gross.get(i),
                    deduction, gross.get(i) - deduction));
        }
        return new SalePlan(quantityKg, pricePerKg, deductionPerKg, sellableKg, true, totalAmount, lines, skipped);
    }

    // The same rounding as a sale's total: whole RWF, half up.
    static long totalAmount(BigDecimal quantityKg, long pricePerKg) {
        return quantityKg.multiply(BigDecimal.valueOf(pricePerKg)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static long deduction(BigDecimal kg, long deductionPerKg) {
        return kg.multiply(BigDecimal.valueOf(deductionPerKg)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
