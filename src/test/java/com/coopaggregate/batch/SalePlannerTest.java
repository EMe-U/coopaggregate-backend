package com.coopaggregate.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.coopaggregate.error.BadRequestException;

class SalePlannerTest {

    // Oldest first, in two lots of the same grade.
    private static final SellableBatch OLDEST = batch(1L, "RCT-0001", 3L, "A-2026-01", 5L, "100.00");
    private static final SellableBatch SECOND = batch(2L, "RCT-0002", 4L, "A-2026-02", 6L, "50.00");
    private static final SellableBatch NEWEST = batch(3L, "RCT-0003", 4L, "A-2026-02", 5L, "200.00");
    private static final List<SellableBatch> BATCHES = List.of(OLDEST, SECOND, NEWEST);

    @Test
    void kgAreTakenFromTheOldestBatchesFirstWhateverTheirLot() {
        SalePlan plan = SalePlanner.plan(BATCHES, new BigDecimal("180.00"), 300, 5, List.of());

        assertTrue(plan.enough());
        assertEquals(List.of(1L, 2L, 3L), plan.lines().stream().map(line -> line.batch().deliveryId()).toList());
        assertEquals(List.of(new BigDecimal("100.00"), new BigDecimal("50.00"), new BigDecimal("30.00")),
                plan.lines().stream().map(SalePlan.Line::kg).toList());
        assertEquals(new BigDecimal("170.00"), plan.lines().get(2).remainingAfterKg());
        assertEquals(new BigDecimal("350.00"), plan.sellableKg());
    }

    @Test
    void eachMemberGetsKgTimesPriceMinusTheDeductionPerKgTaken() {
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
    void theCreditsAddUpExactlyToTheSaleAmount() {
        List<SellableBatch> batches = List.of(batch(1L, "RCT-0001", 3L, "A-2026-01", 5L, "33.33"),
                batch(2L, "RCT-0002", 3L, "A-2026-01", 6L, "33.33"),
                batch(3L, "RCT-0003", 3L, "A-2026-01", 7L, "33.34"));

        SalePlan plan = SalePlanner.plan(batches, new BigDecimal("100.00"), 301, 5, List.of());

        assertEquals(30_100L, plan.totalAmountRwf());
        assertEquals(30_100L, plan.lines().stream().mapToLong(SalePlan.Line::grossRwf).sum());
        // The rounding remainder goes to the batch with the most kg.
        assertEquals(List.of(10_032L, 10_032L, 10_036L),
                plan.lines().stream().map(SalePlan.Line::grossRwf).toList());
    }

    @Test
    void theTotalIsRoundedLikeASaleTotal() {
        SalePlan plan = SalePlanner.plan(BATCHES, new BigDecimal("0.50"), 301, 5, List.of());

        assertEquals(151L, plan.totalAmountRwf());
        assertEquals(new BigDecimal("99.50"), plan.lines().get(0).remainingAfterKg());
    }

    @Test
    void aMemberNeverOwesForAFewGrams() {
        List<SellableBatch> batches = List.of(batch(1L, "RCT-0001", 3L, "A-2026-01", 5L, "0.10"),
                batch(2L, "RCT-0002", 3L, "A-2026-01", 6L, "0.10"));

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
    void aSkippedBatchIsPassedOver() {
        SalePlan plan = SalePlanner.plan(BATCHES, new BigDecimal("120.00"), 300, 5, List.of(1L));

        assertEquals(List.of(2L, 3L), plan.lines().stream().map(line -> line.batch().deliveryId()).toList());
        assertEquals(List.of(OLDEST), plan.skipped());
        assertEquals(new BigDecimal("250.00"), plan.sellableKg(), "skipped kg are not sellable for this sale");
    }

    @Test
    void skippedKgDoNotCountWhenThereAreNotEnough() {
        SalePlan plan = SalePlanner.plan(BATCHES, new BigDecimal("300.00"), 300, 5, List.of(1L));

        assertFalse(plan.enough());
        assertEquals("Only 250.00 kg of Big can be sold now, but 300.00 kg were asked for. Skipped batches are not "
                + "counted.", plan.shortageMessage("Big", BigDecimal.ZERO));
    }

    @Test
    void aBatchTheSaleWouldNotReachCannotBeSkipped() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> SalePlanner.plan(BATCHES, new BigDecimal("100.00"), 300, 5, List.of(3L)));

        assertEquals("Batch RCT-0003 would not be used by this sale, so it does not need to be skipped.",
                e.getMessage());
    }

    @Test
    void onlySellableBatchesCanBeSkipped() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> SalePlanner.plan(BATCHES, new BigDecimal("100.00"), 300, 5, List.of(1L, 99L)));

        assertEquals("Only sellable batches of this grade can be skipped: confirmed, not reversed and with kg left.",
                e.getMessage());
    }

    @Test
    void aPriceAtOrBelowTheDeductionIsRefused() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> SalePlanner.plan(BATCHES, new BigDecimal("10.00"), 5, 5, List.of()));

        assertEquals("The price must be more than the deduction of 5 RWF per kg, otherwise the members would earn "
                + "nothing.", e.getMessage());
    }

    private static SellableBatch batch(Long id, String receiptCode, Long lotId, String lotCode, Long memberId,
                                       String remainingKg) {
        return new SellableBatch(id, receiptCode, lotId, lotCode, memberId, "M-000" + memberId, "Member " + memberId,
                Instant.parse("2026-10-01T08:00:00Z").plusSeconds(id * 3600), new BigDecimal(remainingKg));
    }
}
