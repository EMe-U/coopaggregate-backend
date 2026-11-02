package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.lot.ProportionalKg;
import com.coopaggregate.share.ShareCalculator;

/**
 * Plans a sale of one grade. Plain code without the database: the caller passes every sellable portion of the grade.
 *
 * Lots from before fixed-size lots are sold first, as before: oldest delivery first (delivered time, then id). Then
 * the fixed-size lots, in the order they were filled (lot id). A fixed-size lot is one pool: the kg taken from it are
 * split across its portions in proportion to what each has left (ProportionalKg). The sale amount and the deduction are
 * split across the lines by kg (ShareCalculator.split), so the lines add up to exactly the sale's amount and deduction.
 */
public final class SalePlanner {

    private static final Comparator<SellableBatch> OLDEST_FIRST = Comparator
            .comparing(SellableBatch::deliveredAt)
            .thenComparing(SellableBatch::deliveryId)
            .thenComparing(SellableBatch::portionId);

    private SalePlanner() {
    }

    /** What a sale takes from in turn: one portion of an older lot, or the sellable portions of a fixed-size lot. */
    private record Unit(Long lotId, String lotCode, List<SellableBatch> portions) {

        BigDecimal kg() {
            return portions.stream().map(SellableBatch::remainingKg).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /**
     * skipLotIds are lots the manager chose not to sell from. Each must be a lot of this grade with sellable kg that
     * the sale would otherwise have reached; the reason for skipping is the caller's to check and record.
     */
    public static SalePlan plan(List<SellableBatch> sellable, BigDecimal quantityKg, long pricePerKg,
                                long deductionPerKg, Collection<Long> skipLotIds) {
        if (pricePerKg <= deductionPerKg) {
            throw new BadRequestException("The price must be more than the deduction of " + deductionPerKg
                    + " RWF per kg, otherwise the members would earn nothing.");
        }
        List<Unit> units = units(sellable);
        Set<Long> skip = Set.copyOf(skipLotIds);
        Map<Long, SalePlan.SkippedLot> skipped = new LinkedHashMap<>();
        for (Unit unit : units) {
            if (skip.contains(unit.lotId())) {
                SalePlan.SkippedLot before = skipped.get(unit.lotId());
                skipped.put(unit.lotId(), new SalePlan.SkippedLot(unit.lotId(), unit.lotCode(),
                        before == null ? unit.kg() : before.sellableKg().add(unit.kg())));
            }
        }
        if (skipped.size() != skip.size()) {
            throw new BadRequestException("Only lots of this grade with sellable kg can be skipped.");
        }

        List<SellableBatch> used = new ArrayList<>();
        List<BigDecimal> kgs = new ArrayList<>();
        BigDecimal sellableKg = BigDecimal.ZERO;
        BigDecimal left = quantityKg;
        int lastUsed = -1;
        for (int i = 0; i < units.size(); i++) {
            Unit unit = units.get(i);
            if (skip.contains(unit.lotId())) {
                continue;
            }
            BigDecimal unitKg = unit.kg();
            sellableKg = sellableKg.add(unitKg);
            if (left.signum() > 0 && unitKg.signum() > 0) {
                BigDecimal take = unitKg.min(left);
                List<BigDecimal> parts = ProportionalKg.split(
                        unit.portions().stream().map(SellableBatch::remainingKg).toList(), take);
                for (int p = 0; p < parts.size(); p++) {
                    if (parts.get(p).signum() > 0) {
                        used.add(unit.portions().get(p));
                        kgs.add(parts.get(p));
                    }
                }
                left = left.subtract(take);
                lastUsed = i;
            }
        }

        List<SalePlan.SkippedLot> skippedLots = List.copyOf(skipped.values());
        if (left.signum() > 0) {
            return new SalePlan(quantityKg, pricePerKg, deductionPerKg, sellableKg, false, 0, List.of(), skippedLots);
        }
        // Skipping is for passing over a lot the sale would have used; anything later is not reached anyway.
        for (SalePlan.SkippedLot lot : skippedLots) {
            int firstUnit = 0;
            while (!units.get(firstUnit).lotId().equals(lot.lotId())) {
                firstUnit++;
            }
            if (firstUnit > lastUsed) {
                throw new BadRequestException("Lot " + lot.lotCode() + " would not be used by this sale, "
                        + "so it does not need to be skipped.");
            }
        }

        long totalAmount = totalAmount(quantityKg, pricePerKg);
        List<Long> gross = ShareCalculator.split(totalAmount, kgs);
        List<Long> deductions = ShareCalculator.split(totalAmount(quantityKg, deductionPerKg), kgs);
        List<SalePlan.Line> lines = new ArrayList<>();
        for (int i = 0; i < used.size(); i++) {
            BigDecimal kg = kgs.get(i);
            // Rounding can only make a deduction bigger than its gross for a few grams; the member never owes.
            long deduction = Math.min(deductions.get(i), gross.get(i));
            lines.add(new SalePlan.Line(used.get(i), kg, used.get(i).remainingKg().subtract(kg), gross.get(i),
                    deduction, gross.get(i) - deduction));
        }
        return new SalePlan(quantityKg, pricePerKg, deductionPerKg, sellableKg, true, totalAmount, lines, skippedLots);
    }

    // Older lots: one unit per portion, oldest delivery first. Then each fixed-size lot as one unit, in fill order,
    // with its portions oldest delivery first (the order ties are given in).
    private static List<Unit> units(List<SellableBatch> sellable) {
        List<Unit> units = new ArrayList<>();
        sellable.stream()
                .filter(batch -> !batch.fixedLot())
                .sorted(OLDEST_FIRST)
                .forEach(batch -> units.add(new Unit(batch.lotId(), batch.lotCode(), List.of(batch))));
        Map<Long, List<SellableBatch>> fixedLots = new LinkedHashMap<>();
        sellable.stream()
                .filter(SellableBatch::fixedLot)
                .sorted(Comparator.comparing(SellableBatch::lotId).thenComparing(OLDEST_FIRST))
                .forEach(batch -> fixedLots.computeIfAbsent(batch.lotId(), id -> new ArrayList<>()).add(batch));
        fixedLots.forEach((lotId, portions) -> units.add(new Unit(lotId, portions.get(0).lotCode(), portions)));
        return units;
    }

    // The same rounding as a sale's total: whole RWF, half up.
    static long totalAmount(BigDecimal quantityKg, long pricePerKg) {
        return quantityKg.multiply(BigDecimal.valueOf(pricePerKg)).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
