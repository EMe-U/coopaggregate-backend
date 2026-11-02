package com.coopaggregate.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.coopaggregate.error.BadRequestException;

class SalePlannerTest {

    // Lots from before fixed-size lots: oldest first, in two lots of the same grade.
    private static final SellableBatch OLDEST = older(1L, "RCT-0001", 3L, "A-2026-01", 5L, "100.00");
    private static final SellableBatch SECOND = older(2L, "RCT-0002", 4L, "A-2026-02", 6L, "50.00");
    private static final SellableBatch NEWEST = older(3L, "RCT-0003", 4L, "A-2026-02", 5L, "200.00");
    private static final List<SellableBatch> BATCHES = List.of(OLDEST, SECOND, NEWEST);

    // --- Lots from before fixed-size lots: oldest delivery first, as before ---

    @Test
    void kgAreTakenFromTheOldestBatchesFirstWhateverTheirOlderLot() {
        SalePlan plan = SalePlanner.plan(BATCHES, new BigDecimal("180.00"), 300, 5, List.of());

        assertTrue(plan.enough());
        assertEquals(List.of(1L, 2L, 3L), plan.lines().stream().map(line -> line.batch().deliveryId()).toList());
        assertEquals(List.of(new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("30.00")),
                plan.lines().stream().map(SalePlan.Line::kg).toList());
        assertEquals(new BigDecimal("170.00"), plan.lines().get(2).remainingAfterKg());
        assertEquals(new BigDecimal("350.00"), plan.sellableKg());
    }

    @Test
    void eachMemberGetsTheirPartOfTheAmountAndOfTheDeduction() {
        SalePlan plan = SalePlanner.plan(BATCHES, new BigDecimal("180.00"), 300, 5, List.of());

        assertEquals(54_000L, plan.totalAmountRwf());
        assertEquals(new SalePlan.Line(OLDEST, new BigDecimal("100.00"), new BigDecimal("0.00"), 30_000, 500, 29_500),
                plan.lines().get(0));
        assertEquals(new SalePlan.Line(SECOND, new BigDecimal("50.00"), new BigDecimal("0.00"), 15_000, 250, 14_750),
                plan.lines().get(1));
        assertEquals(new SalePlan.Line(NEWEST, new BigDecimal("30.00"), new BigDecimal("170.00"), 9_000, 150, 8_850),
                plan.lines().get(2));
        assertEquals(900L, plan.totalDeductionRwf());
        assertEquals(53_100L, plan.totalNetRwf());
    }

    @Test
    void theCreditsAddUpExactlyToTheSaleAmountAndDeduction() {
        List<SellableBatch> batches = List.of(older(1L, "RCT-0001", 3L, "A-2026-01", 5L, "33.33"),
                older(2L, "RCT-0002", 3L, "A-2026-01", 6L, "33.33"),
                older(3L, "RCT-0003", 3L, "A-2026-01", 7L, "33.34"));

        SalePlan plan = SalePlanner.plan(batches, new BigDecimal("100.00"), 301, 5, List.of());

        assertEquals(30_100L, plan.totalAmountRwf());
        assertEquals(30_100L, plan.lines().stream().mapToLong(SalePlan.Line::grossRwf).sum());
        // The rounding remainder goes to the batch with the most kg.
        assertEquals(List.of(10_032L, 10_032L, 10_036L),
                plan.lines().stream().map(SalePlan.Line::grossRwf).toList());
        assertEquals(500L, plan.totalDeductionRwf());
    }

    @Test
    void theTotalIsRoundedLikeASaleTotal() {
        SalePlan plan = SalePlanner.plan(BATCHES, new BigDecimal("0.50"), 301, 5, List.of());

        assertEquals(151L, plan.totalAmountRwf());
        assertEquals(new BigDecimal("99.50"), plan.lines().get(0).remainingAfterKg());
    }

    @Test
    void aMemberNeverOwesForAFewGrams() {
        List<SellableBatch> batches = List.of(older(1L, "RCT-0001", 3L, "A-2026-01", 5L, "0.10"),
                older(2L, "RCT-0002", 3L, "A-2026-01", 6L, "0.10"));

        SalePlan plan = SalePlanner.plan(batches, new BigDecimal("0.20"), 6, 5, List.of());

        for (SalePlan.Line line : plan.lines()) {
            assertTrue(line.deductionRwf() <= line.grossRwf(), line.toString());
            assertTrue(line.netRwf() >= 0, line.toString());
        }
        assertEquals(1L, plan.lines().stream().mapToLong(SalePlan.Line::grossRwf).sum());
    }

    @Test
    void allTheSellableKgCanBeSold() {
        SalePlan plan = SalePlanner.plan(BATCHES, new BigDecimal("350.00"), 300, 5, List.of());

        assertTrue(plan.enough());
        assertTrue(plan.lines().stream().allMatch(line -> line.remainingAfterKg().signum() == 0));
    }

    @Test
    void notEnoughSellableKgPlansNothing() {
        SalePlan plan = SalePlanner.plan(BATCHES, new BigDecimal("350.01"), 300, 5, List.of());

        assertFalse(plan.enough());
        assertEquals(List.of(), plan.lines());
        assertEquals(0L, plan.totalAmountRwf());
        assertEquals("Only 350.00 kg of Big can be sold now, but 350.01 kg were asked for. 120.50 kg are waiting "
                + "for their members to confirm them.", plan.shortageMessage("Big", new BigDecimal("120.50")));
        assertEquals("Only 350.00 kg of Big can be sold now, but 350.01 kg were asked for.",
                plan.shortageMessage("Big", BigDecimal.ZERO));
    }

    @Test
    void aGradeWithNoSellableBatchHasNothingToSell() {
        SalePlan plan = SalePlanner.plan(List.of(), new BigDecimal("1.00"), 300, 5, List.of());

        assertFalse(plan.enough());
        assertEquals(BigDecimal.ZERO, plan.sellableKg());
    }

    @Test
    void aPriceAtOrBelowTheDeductionIsRefused() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> SalePlanner.plan(BATCHES, new BigDecimal("10.00"), 5, 5, List.of()));

        assertEquals("The price must be more than the deduction of 5 RWF per kg, otherwise the members would earn "
                + "nothing.", e.getMessage());
    }

    // --- Fixed-size lots: in fill order, each one shared in proportion ---

    @Test
    void fixedSizeLotsAreSoldInTheOrderTheyWereFilledAfterTheOlderLots() {
        List<SellableBatch> sellable = new ArrayList<>(List.of(
                fixed(10L, 11L, 8L, "A-2026-04", 5L, "2026-09-01T08:00:00Z", "100.00"),
                fixed(20L, 21L, 7L, "A-2026-03", 6L, "2026-10-09T08:00:00Z", "100.00"),
                OLDEST));

        SalePlan plan = SalePlanner.plan(sellable, new BigDecimal("150.00"), 300, 5, List.of());

        // Older lot first, then lot 7 (filled first), though lot 8's delivery is older.
        assertEquals(List.of(1L, 21L), plan.lines().stream().map(line -> line.batch().deliveryId()).toList());
        assertEquals(new BigDecimal("50.00"), plan.lines().get(1).kg());
    }

    @Test
    void aPartOfAFixedSizeLotIsSharedInProportionToWhatEachPortionHasLeft() {
        List<SellableBatch> lot = List.of(
                fixed(1L, 1L, 7L, "A-2026-03", 5L, "2026-10-01T08:00:00Z", "20.00"),
                fixed(2L, 2L, 7L, "A-2026-03", 6L, "2026-10-02T08:00:00Z", "50.00"),
                fixed(3L, 3L, 7L, "A-2026-03", 7L, "2026-10-03T08:00:00Z", "30.00"));

        SalePlan plan = SalePlanner.plan(lot, new BigDecimal("50.00"), 300, 5, List.of());

        assertEquals(List.of(new BigDecimal("10.00"), new BigDecimal("25.00"), new BigDecimal("15.00")),
                plan.lines().stream().map(SalePlan.Line::kg).toList());
        assertEquals(List.of(3_000L, 7_500L, 4_500L), plan.lines().stream().map(SalePlan.Line::grossRwf).toList());
    }

    @Test
    void theLastHundredthsOfAShareGoToTheLargestRemainderThenTheOlderDelivery() {
        // Listed newest first on purpose: the planner orders them oldest first itself.
        List<SellableBatch> lot = List.of(
                fixed(3L, 3L, 7L, "A-2026-03", 7L, "2026-10-03T08:00:00Z", "5.00"),
                fixed(2L, 2L, 7L, "A-2026-03", 6L, "2026-10-02T08:00:00Z", "5.00"),
                fixed(1L, 1L, 7L, "A-2026-03", 5L, "2026-10-01T08:00:00Z", "5.00"));

        SalePlan plan = SalePlanner.plan(lot, new BigDecimal("10.00"), 300, 5, List.of());

        assertEquals(List.of(1L, 2L, 3L), plan.lines().stream().map(line -> line.batch().deliveryId()).toList());
        assertEquals(List.of(new BigDecimal("3.34"), new BigDecimal("3.33"), new BigDecimal("3.33")),
                plan.lines().stream().map(SalePlan.Line::kg).toList());
    }

    @Test
    void aDeliverySplitAcrossTwoLotsIsSoldAsTwoPortions() {
        List<SellableBatch> sellable = List.of(
                fixed(11L, 1L, 7L, "A-2026-03", 5L, "2026-10-01T08:00:00Z", "30.00"),
                fixed(12L, 1L, 8L, "A-2026-04", 5L, "2026-10-01T08:00:00Z", "20.00"),
                fixed(13L, 2L, 7L, "A-2026-03", 6L, "2026-09-30T08:00:00Z", "70.00"));

        SalePlan plan = SalePlanner.plan(sellable, new BigDecimal("110.00"), 300, 5, List.of());

        assertEquals(List.of(13L, 11L, 12L), plan.lines().stream().map(line -> line.batch().portionId()).toList());
        assertEquals(List.of(new BigDecimal("70.00"), new BigDecimal("30.00"), new BigDecimal("10.00")),
                plan.lines().stream().map(SalePlan.Line::kg).toList());
    }

    // --- Skipping whole lots ---

    @Test
    void aSkippedOlderLotIsPassedOverWithAllItsBatches() {
        SalePlan plan = SalePlanner.plan(BATCHES, new BigDecimal("100.00"), 300, 5, List.of(3L));

        assertEquals(List.of(2L, 3L), plan.lines().stream().map(line -> line.batch().deliveryId()).toList());
        assertEquals(List.of(new SalePlan.SkippedLot(3L, "A-2026-01", new BigDecimal("100.00"))), plan.skipped());
        assertEquals(new BigDecimal("250.00"), plan.sellableKg(), "skipped kg are not sellable for this sale");
    }

    @Test
    void skippingAnOlderLotSkipsEveryOneOfItsBatches() {
        SalePlan plan = SalePlanner.plan(BATCHES, new BigDecimal("110.00"), 300, 5, List.of(4L));

        // Both batches of lot A-2026-02 (50 and 200 kg) are passed over; only lot A-2026-01's 100 kg are left.
        assertFalse(plan.enough());
        assertEquals(new BigDecimal("100.00"), plan.sellableKg());
        assertEquals(List.of(new SalePlan.SkippedLot(4L, "A-2026-02", new BigDecimal("250.00"))), plan.skipped());
    }

    @Test
    void aSkippedFixedSizeLotIsPassedOver() {
        List<SellableBatch> sellable = List.of(
                fixed(1L, 1L, 7L, "A-2026-03", 5L, "2026-10-01T08:00:00Z", "100.00"),
                fixed(2L, 2L, 8L, "A-2026-04", 6L, "2026-10-02T08:00:00Z", "40.00"));

        SalePlan plan = SalePlanner.plan(sellable, new BigDecimal("40.00"), 300, 5, List.of(7L));

        assertEquals(List.of(2L), plan.lines().stream().map(line -> line.batch().deliveryId()).toList());
        assertEquals(List.of(new SalePlan.SkippedLot(7L, "A-2026-03", new BigDecimal("100.00"))), plan.skipped());
    }

    @Test
    void skippedKgDoNotCountWhenThereAreNotEnough() {
        SalePlan plan = SalePlanner.plan(BATCHES, new BigDecimal("300.00"), 300, 5, List.of(3L));

        assertFalse(plan.enough());
        assertEquals("Only 250.00 kg of Big can be sold now, but 300.00 kg were asked for. Skipped lots are not "
                + "counted.", plan.shortageMessage("Big", BigDecimal.ZERO));
    }

    @Test
    void aLotTheSaleWouldNotReachCannotBeSkipped() {
        List<SellableBatch> sellable = List.of(
                fixed(1L, 1L, 7L, "A-2026-03", 5L, "2026-10-01T08:00:00Z", "100.00"),
                fixed(2L, 2L, 8L, "A-2026-04", 6L, "2026-10-02T08:00:00Z", "40.00"));

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> SalePlanner.plan(sellable, new BigDecimal("50.00"), 300, 5, List.of(8L)));

        assertEquals("Lot A-2026-04 would not be used by this sale, so it does not need to be skipped.",
                e.getMessage());
    }

    @Test
    void onlyLotsWithSellableKgCanBeSkipped() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> SalePlanner.plan(BATCHES, new BigDecimal("100.00"), 300, 5, List.of(3L, 99L)));

        assertEquals("Only lots of this grade with sellable kg can be skipped.", e.getMessage());
    }

    // --- Everything adds up, whatever the numbers ---

    @Test
    void anyPlanAddsUpExactlyAndTakesTheLotsInOrder() {
        Random random = new Random(11);
        for (int run = 0; run < 500; run++) {
            List<SellableBatch> sellable = new ArrayList<>();
            Map<Long, BigDecimal> lotKg = new HashMap<>();
            long portionId = 1;
            for (long lotId = 1; lotId <= 1 + random.nextInt(5); lotId++) {
                for (int i = 0; i < 1 + random.nextInt(12); i++) {
                    BigDecimal kg = BigDecimal.valueOf(1 + random.nextInt(9_000), 2);
                    sellable.add(fixed(portionId, portionId, lotId, "L-" + lotId, (long) random.nextInt(6),
                            "2026-10-01T08:00:00Z", kg.toPlainString()));
                    portionId++;
                    lotKg.merge(lotId, kg, BigDecimal::add);
                }
            }
            Collections.shuffle(sellable, random);
            BigDecimal all = lotKg.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal quantity = BigDecimal.valueOf(1 + (long) (random.nextDouble() * all.movePointRight(2)
                    .longValueExact()), 2);
            long price = 6 + random.nextInt(997);

            SalePlan plan = SalePlanner.plan(sellable, quantity, price, 5, List.of());

            assertTrue(plan.enough());
            assertEquals(0, quantity.compareTo(plan.lines().stream().map(SalePlan.Line::kg)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)));
            long total = quantity.multiply(BigDecimal.valueOf(price)).setScale(0, RoundingMode.HALF_UP).longValueExact();
            assertEquals(total, plan.totalAmountRwf());
            assertEquals(total, plan.lines().stream().mapToLong(SalePlan.Line::grossRwf).sum());
            assertEquals(total, plan.totalDeductionRwf() + plan.totalNetRwf());
            for (SalePlan.Line line : plan.lines()) {
                assertTrue(line.kg().signum() > 0 && line.remainingAfterKg().signum() >= 0, line.toString());
                assertTrue(line.netRwf() >= 0 && line.deductionRwf() >= 0, line.toString());
            }
            // Lots are taken in id order, and every lot before the last one used is emptied.
            List<Long> lotsUsed = plan.lines().stream().map(line -> line.batch().lotId()).distinct().toList();
            assertEquals(lotsUsed.stream().sorted().toList(), lotsUsed);
            for (Long lotId : lotsUsed.subList(0, lotsUsed.size() - 1)) {
                assertEquals(0, lotKg.get(lotId).compareTo(plan.lines().stream()
                        .filter(line -> line.batch().lotId().equals(lotId)).map(SalePlan.Line::kg)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)));
            }
        }
    }

    private static SellableBatch older(Long id, String receiptCode, Long lotId, String lotCode, Long memberId,
                                       String remainingKg) {
        return new SellableBatch(id, id, receiptCode, lotId, lotCode, false, memberId, "M-000" + memberId,
                "Member " + memberId, Instant.parse("2026-10-01T08:00:00Z").plusSeconds(id * 3600),
                new BigDecimal(remainingKg));
    }

    private static SellableBatch fixed(Long portionId, Long deliveryId, Long lotId, String lotCode, Long memberId,
                                       String deliveredAt, String remainingKg) {
        return new SellableBatch(portionId, deliveryId, "RCT-" + deliveryId, lotId, lotCode, true, memberId,
                "M-000" + memberId, "Member " + memberId, Instant.parse(deliveredAt), new BigDecimal(remainingKg));
    }
}
