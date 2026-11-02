package com.coopaggregate.sale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.batch.BatchAllocator;
import com.coopaggregate.batch.LossAllocation;
import com.coopaggregate.batch.LossAllocationRepository;
import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.loss.Loss;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotStatus;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

class SaleServiceTest {

    private static final UUID CLIENT_UUID = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    private SaleTestFixture f;
    private SaleService service;

    @BeforeEach
    void setUp() {
        f = new SaleTestFixture();
        f.stockOfBig();
        service = f.service;
    }

    // --- Taking kg from the oldest batches ---

    @Test
    void kgComeFromTheOldestBatchesWhateverTheirLot() {
        RecordedSale result = sell("120.00", 300L);

        assertTrue(result.created());
        SaleResponse sale = result.sale();
        assertEquals(11L, sale.id());
        assertTrue(sale.saleCode().matches("SAL-[2-9A-HJKMNP-Z]{4}"), sale.saleCode());
        assertEquals("Big", sale.gradeName());
        assertNull(sale.lotId(), "a sale by grade has no lot of its own");
        // Batch 2 (lot A-2) is the oldest, then batch 1 (lot A-1); batch 3 is not reached.
        assertEquals(List.of(2L, 1L), sale.batches().stream().map(SaleResponse.BatchLine::deliveryId).toList());
        assertEquals(List.of("A-2026-02", "A-2026-01"),
                sale.batches().stream().map(SaleResponse.BatchLine::lotCode).toList());
        assertEquals(new BigDecimal("50.00"), f.deliveries.get(1).getSoldKg());
        assertEquals(new BigDecimal("70.00"), f.deliveries.get(0).getSoldKg());
        assertEquals(BigDecimal.ZERO, f.deliveries.get(2).getSoldKg());
    }

    @Test
    void theLastKgCanBeSoldAndEmptiedLotsBecomeSold() {
        SaleResponse sale = sell("250.00", 300L).sale();

        assertEquals(3, sale.batches().size());
        f.deliveries.forEach(batch -> assertEquals(0, batch.unallocatedKg().signum()));
        // The closed lot and the open lot are both sold, closing was not needed; nothing was closed by hand.
        assertEquals(LotStatus.SOLD, f.lotA1.getStatus());
        assertEquals(LotStatus.SOLD, f.lotA2.getStatus());
        assertEquals(KigaliTime.today(), f.lotA2.getCloseDate());
    }

    @Test
    void aLotWithKgLeftKeepsItsStatus() {
        sell("120.00", 300L);

        assertEquals(LotStatus.CLOSED, f.lotA1.getStatus());
        // Lot A-2 sold its only batch of 50 kg completely.
        assertEquals(LotStatus.SOLD, f.lotA2.getStatus());
    }

    @Test
    void pendingAndDisputedBatchesAreNotSold() {
        f.pendingBatch(9L, f.lotA1, f.alice, "2026-09-01T08:00:00Z", "500.00");

        BadRequestException e = assertThrows(BadRequestException.class, () -> sell("300.00", 300L));

        assertEquals("Only 250.00 kg of Big can be sold now, but 300.00 kg were asked for. 500.00 kg are waiting for "
                + "their members to confirm them.", e.getMessage());
    }

    @Test
    void notEnoughSellableKgIs400AndSavesNothing() {
        assertThrows(BadRequestException.class, () -> sell("250.01", 300L));

        assertNothingSaved();
        f.deliveries.forEach(batch -> assertEquals(BigDecimal.ZERO, batch.getSoldKg()));
        assertEquals(LotStatus.CLOSED, f.lotA1.getStatus());
    }

    @Test
    void aPriceAtOrBelowTheDeductionIs400AndSavesNothing() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> sell("10.00", 5L));

        assertEquals("The price must be more than the deduction of 5 RWF per kg, otherwise the members would earn "
                + "nothing.", e.getMessage());
        assertNothingSaved();
    }

    @Test
    void aSaleOfAnUnknownGradeOrBuyerIs400AndSavesNothing() {
        assertEquals("Grade 99 does not exist.", assertThrows(BadRequestException.class,
                () -> service.record(request(99L, 2L, "10.00", 300L, null, null), f.manager)).getMessage());
        assertEquals("Buyer 99 does not exist.", assertThrows(BadRequestException.class,
                () -> service.record(request(1L, 99L, "10.00", 300L, null, null), f.manager)).getMessage());
        assertNothingSaved();
    }

    // --- Money ---

    @Test
    void creditsAddUpExactlyToTheSaleTotal() {
        SaleResponse sale = sell("120.00", 301L).sale();

        assertEquals(36_120L, sale.totalAmountRwf());
        assertEquals(36_120L, sale.batches().stream().mapToLong(SaleResponse.BatchLine::grossRwf).sum());
        assertEquals(36_120L, f.ledger.get(0).amount());
        // Each member: their part of the amount minus 5 RWF per kg taken.
        assertEquals(new SaleResponse.BatchLine(2L, "RCT-0002", 4L, "A-2026-02", 6L, "M-0006", "Habimana Bosco",
                new BigDecimal("50.00"), 15_050L, 250L, 14_800L), sale.batches().get(0));
        assertEquals(new SaleResponse.BatchLine(1L, "RCT-0001", 3L, "A-2026-01", 5L, "M-0005", "Uwimana Claudine",
                new BigDecimal("70.00"), 21_070L, 350L, 20_720L), sale.batches().get(1));
        assertEquals(600L, sale.totalDeductionRwf());
        assertEquals(35_520L, sale.totalNetRwf());
        assertEquals(sale.totalAmountRwf(), sale.totalDeductionRwf() + sale.totalNetRwf());
    }

    @Test
    void theSaleTotalIsKilogramsTimesPriceRoundedToWholeRwf() {
        assertEquals(150L, sell("0.50", 300L).sale().totalAmountRwf());
    }

    @ParameterizedTest
    @CsvSource({"100.00, 300, 30000", "0.01, 300, 3", "12.25, 310, 3798", "12.30, 310, 3813"})
    void totalIsKilogramsTimesPriceRoundedToWholeRwf(String quantityKg, long pricePerKg, long expectedTotal) {
        assertEquals(expectedTotal, sell(quantityKg, pricePerKg).sale().totalAmountRwf());
    }

    @Test
    void membersOfOlderSalesDoNotChange() {
        // The batch of the first sale is used up before the second sale starts.
        sell("50.00", 300L);
        SaleResponse second = service.record(new SaleRequest(UUID.randomUUID(), 1L, 2L, new BigDecimal("60.00"), 320L,
                null, null, null, null), f.manager).sale();

        assertEquals(List.of(1L), second.batches().stream().map(SaleResponse.BatchLine::deliveryId).toList());
        assertEquals(new BigDecimal("60.00"), f.deliveries.get(0).getSoldKg());
        assertEquals(new BigDecimal("50.00"), f.deliveries.get(1).getSoldKg());
    }

    @Test
    void priceBelowFloorIsFlaggedButSaved() {
        f.big.setFloorPricePerKg(300L);

        assertTrue(sell("100.00", 299L).sale().belowFloor());
    }

    @Test
    void priceAtOrAboveFloorIsNotFlagged() {
        f.big.setFloorPricePerKg(300L);

        assertFalse(sell("100.00", 300L).sale().belowFloor());
    }

    @Test
    void gradeWithoutFloorPriceIsNeverBelowFloor() {
        f.big.setFloorPricePerKg(null);

        assertFalse(sell("100.00", 6L).sale().belowFloor());
    }

    // --- The ledger ---

    @Test
    void saleIsRecordedInLedgerThenOneCreditPerAllocation() {
        SaleResponse sale = sell("120.00", 300L).sale();

        assertEquals(3, f.ledger.size());
        SaleTestFixture.Ledgered saleEntry = f.ledger.get(0);
        assertEquals(LedgerEntryType.SALE, saleEntry.type());
        assertEquals("SALE", saleEntry.relatedType());
        assertEquals(11L, saleEntry.relatedId());
        assertEquals(new BigDecimal("120.00"), saleEntry.kg());
        assertEquals(36_000L, saleEntry.amount());
        assertEquals("Sale " + sale.saleCode() + " of Big to Kalisa Traders", saleEntry.reason());
    }

    @Test
    void everyAllocationHasExactlyOneCreditOfItsNetAmount() {
        sell("120.00", 300L);

        assertEquals(2, f.savedAllocations.size());
        List<SaleTestFixture.Ledgered> credits = f.credits();
        assertEquals(f.savedAllocations.size(), credits.size());
        for (SaleAllocation allocation : f.savedAllocations) {
            List<SaleTestFixture.Ledgered> own = credits.stream()
                    .filter(credit -> credit.relatedId().equals(allocation.getId())).toList();
            assertEquals(1, own.size(), "credits of allocation " + allocation.getId());
            assertEquals("SALE_ALLOCATION", own.get(0).relatedType());
            assertEquals(allocation.getQuantityKg(), own.get(0).kg());
            assertEquals(allocation.netRwf(), own.get(0).amount());
        }
        assertEquals(Set.of(101L, 102L), credits.stream().map(SaleTestFixture.Ledgered::relatedId)
                .collect(Collectors.toSet()));
        // The reason holds codes only, never a name.
        assertEquals("Credit for sale " + f.savedSales.get(0).getSaleCode() + " from RCT-0002",
                credits.get(0).reason());
    }

    @Test
    void locksAreTakenGradeThenLotsInIdOrderThenPortionsThenDeliveriesThenLedger() {
        sell("120.00", 300L);

        // The oldest batch is in lot 4, but lot 3 is locked first.
        assertEquals(List.of("grade:1", "lot:3", "lot:4", "portions", "deliveries", "sale", "allocation",
                "allocation", "ledger:SALE", "ledger:SALE_CREDIT", "ledger:SALE_CREDIT"), f.calls);
    }

    @Test
    void lotsWithoutSellableBatchesAreNotLocked() {
        f.deliveries.get(1).addSoldKg(new BigDecimal("50.00"));
        f.portionOf(f.deliveries.get(1), f.lotA2).addSoldKg(new BigDecimal("50.00"));

        sell("10.00", 300L);

        assertEquals(List.of("grade:1", "lot:3", "portions", "deliveries"), f.calls.subList(0, 4));
        verify(f.lotRepository, never()).findForUpdateById(4L);
    }

    // --- Repeating a request ---

    @Test
    void sameClientUuidReturnsTheFirstSaleAndChangesNothing() {
        SaleResponse first = sell("120.00", 300L).sale();
        f.calls.clear();
        f.ledger.clear();
        when(f.saleRepository.findByClientUuid(CLIENT_UUID)).thenReturn(Optional.of(f.savedSales.get(0)));

        RecordedSale again = sell("120.00", 300L);

        assertFalse(again.created());
        assertEquals(first.id(), again.sale().id());
        assertEquals(first.batches(), again.sale().batches());
        assertEquals(1, f.savedSales.size());
        assertEquals(2, f.savedAllocations.size());
        assertEquals(new BigDecimal("70.00"), f.deliveries.get(0).getSoldKg(), "no kg were taken twice");
        assertTrue(f.ledger.isEmpty());
        // Only the grade was locked, so the repeat waits for the first request.
        assertEquals(List.of("grade:1"), f.calls);
    }

    // --- Skipping whole lots ---

    @Test
    void aSkippedLotIsPassedOverAndTheSkipIsRecordedInTheAuditLog() {
        SaleResponse sale = service.record(request(1L, 2L, "120.00", 300L, List.of(4L),
                "  Customer wants potatoes from lot A-1 only  "), f.manager).sale();

        assertEquals(List.of(1L, 3L), sale.batches().stream().map(SaleResponse.BatchLine::deliveryId).toList());
        assertEquals(BigDecimal.ZERO, f.deliveries.get(1).getSoldKg());
        assertEquals(1, f.audits.size());
        assertEquals(AuditAction.SALE_FIFO_SKIPPED, f.audits.get(0).getAction());
        assertEquals("Sale " + sale.saleCode() + ": skipped lots A-2026-02. Reason: Customer wants potatoes from lot "
                + "A-1 only", f.audits.get(0).getDetails());
        assertNull(f.audits.get(0).getMember());
    }

    @Test
    void singleBatchesCanNoLongerBeSkipped() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.record(new SaleRequest(
                CLIENT_UUID, 1L, 2L, new BigDecimal("120.00"), 300L, null, null, "Customer wants Alice's potatoes",
                List.of(2L)), f.manager));

        assertEquals("Single batches can no longer be skipped. Skip whole lots with skipLotIds.", e.getMessage());
        assertTrue(f.calls.isEmpty());
        assertNothingSaved();
    }

    @Test
    void aSaleWithoutSkipsWritesNoAuditEntry() {
        sell("120.00", 300L);

        assertTrue(f.audits.isEmpty());
    }

    @Test
    void skippingNeedsAReasonOf10To500Characters() {
        String message = "A reason of 10 to 500 characters is needed to skip a lot.";
        for (String reason : new String[] {null, "", "         ", "too short", "x".repeat(501)}) {
            assertEquals(message, assertThrows(BadRequestException.class, () -> service.record(
                    request(1L, 2L, "120.00", 300L, List.of(4L), reason), f.manager)).getMessage(),
                    String.valueOf(reason));
        }

        // Refused before anything is locked or saved.
        assertTrue(f.calls.isEmpty());
        assertNothingSaved();
        assertEquals(BigDecimal.ZERO, f.deliveries.get(1).getSoldKg());
    }

    @Test
    void aReasonWithoutSkipsIsIgnored() {
        service.record(request(1L, 2L, "120.00", 300L, List.of(), "not needed"), f.manager);

        assertTrue(f.audits.isEmpty());
    }

    @Test
    void aSkippedLotTheSaleWouldNotReachIsRefusedAndSavesNothing() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.record(
                request(1L, 2L, "40.00", 300L, List.of(3L), "Wants the older potatoes"), f.manager));

        assertEquals("Lot A-2026-01 would not be used by this sale, so it does not need to be skipped.",
                e.getMessage());
        assertNothingSaved();
        assertTrue(f.audits.isEmpty());
    }

    // --- Fixed-size lots ---

    // Grade Small has no older stock with kg here: B-1 is emptied, so only the fixed-size lots below are sold.
    private void fixedLotsOfSmall() {
        Lot first = f.fixedLot(20L, "B-2026-02", f.small, LotStatus.FULL);
        Lot second = f.fixedLot(21L, "B-2026-03", f.small, LotStatus.FILLING);
        f.batch(31L, first, f.alice, "2026-10-05T08:00:00Z", "60.00");
        f.splitBatch(32L, f.bosco, "2026-10-04T08:00:00Z", first, "40.00", second, "10.00");
        f.batch(33L, second, f.alice, "2026-10-06T08:00:00Z", "30.00");
    }

    @Test
    void aSaleOf150KgTakesTheFirstLotWholeAndPartOfTheNextSharedInProportion() {
        fixedLotsOfSmall();
        Lot first = f.lots.get(20L);
        Lot second = f.lots.get(21L);
        Lot third = f.fixedLot(22L, "B-2026-04", f.small, LotStatus.FILLING);
        second.setStatus(LotStatus.FULL);
        f.batch(34L, second, f.bosco, "2026-10-07T08:00:00Z", "60.00");
        f.batch(35L, third, f.alice, "2026-10-08T08:00:00Z", "20.00");

        SaleResponse sale = service.record(request(2L, 2L, "150.00", 300L, null, null), f.manager).sale();

        // Lot B-2026-02 (100 kg) whole, then 50 of the 100 kg of B-2026-03 in proportion: 10, 30 and 60 kg left give
        // 5, 15 and 30 kg. B-2026-04 is not reached.
        // Inside a lot, the older delivery (32, delivered on 4 October) is listed first.
        assertEquals(List.of("B-2026-02:32:40.00", "B-2026-02:31:60.00", "B-2026-03:32:5.00", "B-2026-03:33:15.00",
                "B-2026-03:34:30.00"), sale.batches().stream()
                .map(line -> line.lotCode() + ":" + line.deliveryId() + ":" + line.kg().toPlainString()).toList());
        assertEquals(new BigDecimal("45.00"), f.deliveries.get(f.deliveries.size() - 4).getSoldKg());
        assertEquals(LotStatus.SOLD, first.getStatus());
        assertEquals(LotStatus.FULL, second.getStatus());
        assertEquals(0, f.portionOf(f.deliveries.get(f.deliveries.size() - 4), second).getSoldKg()
                .compareTo(new BigDecimal("5.00")));
        f.savedAllocations.forEach(allocation -> assertTrue(allocation.getLotPortion() != null));
    }

    @Test
    void aLotStillBeingFilledCanSellAndKeepsFilling() {
        fixedLotsOfSmall();
        Lot second = f.lots.get(21L);

        service.record(request(2L, 2L, "140.00", 300L, null, null), f.manager);

        assertEquals(LotStatus.SOLD, f.lots.get(20L).getStatus());
        assertEquals(LotStatus.FILLING, second.getStatus(), "a lot being filled is never marked sold");
        assertEquals(0, f.lotPortionRepository.remainingKg(21L).signum());
    }

    @Test
    void olderLotsAreSoldFirstOldestDeliveryFirstThenTheFixedSizeLots() {
        Lot fixed = f.fixedLot(20L, "A-2026-03", f.big, LotStatus.FULL);
        // Delivered before every batch of the older lots, but in a fixed-size lot: still sold after them.
        f.batch(40L, fixed, f.alice, "2026-09-01T08:00:00Z", "100.00");

        SaleResponse sale = sell("260.00", 300L).sale();

        assertEquals(List.of(2L, 1L, 3L, 40L), sale.batches().stream().map(SaleResponse.BatchLine::deliveryId).toList());
        assertEquals(new BigDecimal("10.00"), sale.batches().get(3).kg());
    }

    @Test
    void disputedPortionsAreHeldBackFromSalesButStayInTheirLot() {
        fixedLotsOfSmall();
        Delivery disputed = f.deliveries.get(f.deliveries.size() - 3);
        disputed.dispute(com.coopaggregate.delivery.DisputeReason.WRONG_KG, null, java.time.Instant.now());

        // Delivery 31 (60 kg) is disputed: of the 140 kg in the two lots, 80 can be sold.
        assertThrows(BadRequestException.class, () -> service.record(request(2L, 2L, "80.01", 300L, null, null),
                f.manager));
        SaleResponse sale = service.record(request(2L, 2L, "80.00", 300L, null, null), f.manager).sale();

        assertTrue(sale.batches().stream().noneMatch(line -> line.deliveryId().equals(disputed.getId())));
        assertEquals(BigDecimal.ZERO, disputed.getSoldKg());
        assertEquals(new BigDecimal("60.00"), f.lotPortionRepository.remainingKg(20L), "its 60 kg are still in B-2026-02");
    }

    @Test
    void skippingAFixedSizeLotPassesOverAllOfItsPortions() {
        fixedLotsOfSmall();

        SaleResponse sale = service.record(request(2L, 2L, "30.00", 300L, List.of(20L), "Buyer wants the newer lot"),
                f.manager).sale();

        assertTrue(sale.batches().stream().allMatch(line -> "B-2026-03".equals(line.lotCode())));
        assertEquals("Sale " + sale.saleCode() + ": skipped lots B-2026-02. Reason: Buyer wants the newer lot",
                f.audits.get(0).getDetails());
    }

    @Test
    void manyMembersAwkwardKilosAndPricesStillAddUpExactly() {
        Lot lot = f.fixedLot(20L, "B-2026-02", f.small, LotStatus.FULL);
        String[] kilos = {"7.13", "11.17", "13.19", "17.23", "19.29", "3.11", "5.07", "23.81"};
        for (int i = 0; i < kilos.length; i++) {
            f.batch(50L + i, lot, i % 2 == 0 ? f.alice : f.bosco, "2026-10-0" + (1 + i) + "T08:00:00Z", kilos[i]);
        }

        SaleResponse sale = service.record(request(2L, 2L, "61.37", 293L, null, null), f.manager).sale();

        assertEquals(0, new BigDecimal("61.37").compareTo(sale.batches().stream().map(SaleResponse.BatchLine::kg)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));
        long total = new BigDecimal("61.37").multiply(BigDecimal.valueOf(293)).setScale(0, java.math.RoundingMode.HALF_UP)
                .longValueExact();
        assertEquals(total, sale.totalAmountRwf());
        assertEquals(total, sale.batches().stream().mapToLong(SaleResponse.BatchLine::grossRwf).sum());
        assertEquals(307L, sale.totalDeductionRwf(), "5 RWF per kg of 61.37 kg, rounded once");
        assertEquals(total, sale.totalDeductionRwf() + sale.totalNetRwf());
        assertLedgerAddsUp();
        f.portions.forEach(portion -> assertTrue(portion.unallocatedKg().signum() >= 0));
    }

    @Test
    void randomSalesAlwaysAddUpExactlyToTheirLedgerEntries() {
        java.util.Random random = new java.util.Random(4);
        long nextId = 100;
        for (int lotNumber = 0; lotNumber < 6; lotNumber++) {
            Lot lot = f.fixedLot(30L + lotNumber, "B-2026-" + (10 + lotNumber), f.small, LotStatus.FULL);
            int left = 10_000;
            while (left > 0) {
                int kg = Math.min(left, 1 + random.nextInt(4_000));
                f.batch(nextId++, lot, random.nextBoolean() ? f.alice : f.bosco, "2026-10-01T08:00:00Z",
                        BigDecimal.valueOf(kg, 2).toPlainString());
                left -= kg;
            }
        }
        for (int sale = 0; sale < 12; sale++) {
            f.ledger.clear();
            String kg = BigDecimal.valueOf(1 + random.nextInt(4_900), 2).toPlainString();
            service.record(new SaleRequest(UUID.randomUUID(), 2L, 2L, new BigDecimal(kg), 6L + random.nextInt(997),
                    null, null, null, null), f.manager);
            assertLedgerAddsUp();
        }
        f.portions.forEach(portion -> assertTrue(portion.unallocatedKg().signum() >= 0, portion.toString()));
        f.deliveries.forEach(delivery -> assertEquals(0, delivery.getSoldKg().compareTo(f.portions.stream()
                .filter(portion -> portion.getDelivery() == delivery).map(portion -> portion.getSoldKg())
                .reduce(BigDecimal.ZERO, BigDecimal::add)), "a delivery's sold kg are its portions' sold kg"));
    }

    // The credits of the last sale: their kg add up to the SALE entry's kg, their gross (net + deduction) to its amount.
    private void assertLedgerAddsUp() {
        SaleTestFixture.Ledgered saleEntry = f.ledger.stream()
                .filter(entry -> entry.type() == LedgerEntryType.SALE).reduce((a, b) -> b).orElseThrow();
        List<SaleAllocation> allocations = f.savedAllocations.stream()
                .filter(allocation -> allocation.getSale().getId().equals(saleEntry.relatedId())).toList();
        assertEquals(0, saleEntry.kg().compareTo(allocations.stream().map(SaleAllocation::getQuantityKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add)));
        assertEquals(saleEntry.amount().longValue(), allocations.stream().mapToLong(SaleAllocation::getGrossRwf).sum());
        assertEquals(saleEntry.amount().longValue(), allocations.stream()
                .mapToLong(allocation -> allocation.netRwf() + allocation.getDeductionRwf()).sum());
        assertEquals(allocations.size(), f.credits().stream()
                .filter(credit -> allocations.stream().anyMatch(a -> a.getId().equals(credit.relatedId()))).count());
        for (SaleAllocation allocation : allocations) {
            assertTrue(allocation.netRwf() >= 0 && allocation.getDeductionRwf() >= 0);
        }
    }

    // --- A sale and a loss on the same lot ---

    @Test
    void aLossOnTheSameLotOnlyTakesWhatTheSaleLeft() {
        sell("120.00", 300L);
        // The sale took 70 kg from batch 1 (lot A-1) and all of batch 2 (lot A-2).
        LossAllocationRepository lossAllocations = mock(LossAllocationRepository.class);
        Loss loss = new Loss();
        ReflectionTestUtils.setField(loss, "id", 1L);
        loss.setLot(f.lotA1);
        loss.setQuantityKg(new BigDecimal("40.00"));

        new BatchAllocator(f.lotPortionRepository, lossAllocations, f.deliveryRepository).allocateLoss(loss);

        // Batch 1 has 30 kg left and batch 3 has 100: the 40 kg are shared 30 to 100, the sold kg carry none.
        Delivery first = f.deliveries.get(0);
        Delivery third = f.deliveries.get(2);
        assertEquals(new BigDecimal("70.00"), first.getSoldKg());
        assertEquals(new BigDecimal("9.23"), first.getLostKg());
        assertEquals(new BigDecimal("30.77"), third.getLostKg());
        assertEquals(new BigDecimal("20.77"), first.unallocatedKg());
        assertEquals(new BigDecimal("69.23"), third.unallocatedKg());
        verify(lossAllocations, times(2)).save(any(LossAllocation.class));
        // The lot has 200 kg: 70 sold, 40 lost, 90 left; the portions say the same.
        assertEquals(new BigDecimal("90.00"), f.lotPortionRepository.remainingKg(3L));
    }

    @Test
    void aSaleAfterALossOnTheSameLotOnlyTakesWhatTheLossLeft() {
        f.deliveries.get(0).addLostKg(new BigDecimal("60.00"));
        f.portionOf(f.deliveries.get(0), f.lotA1).addLostKg(new BigDecimal("60.00"));

        SaleResponse sale = sell("140.00", 300L).sale();

        // Batch 2 (50 kg), then batch 1 with only 40 kg left, then batch 3 with 50 of its 100 kg.
        assertEquals(List.of(new BigDecimal("50.00"), new BigDecimal("40.00"), new BigDecimal("50.00")),
                sale.batches().stream().map(SaleResponse.BatchLine::kg).toList());
        f.deliveries.forEach(batch -> assertTrue(batch.getSoldKg().add(batch.getLostKg())
                .compareTo(batch.getQuantityKg()) <= 0));
    }

    // --- Listing ---

    @Test
    void summaryAddsUpThisMonthsSales() {
        LocalDate first = LocalDate.of(2026, 10, 1);
        LocalDate last = LocalDate.of(2026, 10, 31);
        when(f.saleRepository.totalAmountBetween(first, last)).thenReturn(160250L);
        when(f.saleRepository.totalKgBetween(first, last)).thenReturn(new BigDecimal("500.75"));
        when(f.saleRepository.buyerCountBetween(first, last)).thenReturn(3L);

        SaleSummaryResponse summary = service.summary(LocalDate.of(2026, 10, 9));

        assertEquals(new SaleSummaryResponse(160250L, new BigDecimal("500.75"), 3L, new BigDecimal("320.02")),
                summary);
    }

    @Test
    void averagePriceIsZeroWithoutSales() {
        when(f.saleRepository.totalAmountBetween(any(), any())).thenReturn(0L);
        when(f.saleRepository.totalKgBetween(any(), any())).thenReturn(BigDecimal.ZERO);

        SaleSummaryResponse summary = service.summary(LocalDate.of(2028, 2, 29));

        assertEquals(BigDecimal.ZERO, summary.averagePricePerKg());
        verify(f.saleRepository).totalKgBetween(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29));
    }

    @Test
    void saleResponseListsTheBatchesAndWhatEachMemberWasCredited() {
        SaleResponse sale = sell("120.00", 300L).sale();

        assertEquals(2, sale.batches().size());
        assertEquals(35_400L, sale.totalNetRwf());
        assertEquals(600L, sale.totalDeductionRwf());
    }

    @Test
    void listBuildsTheBatchesOfAWholePageInOneQuery() {
        sell("120.00", 300L);
        Sale other = new Sale();
        ReflectionTestUtils.setField(other, "id", 99L);
        other.setGrade(f.big);
        other.setBuyer(f.buyer);
        when(f.saleRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(f.savedSales.get(0), other)));

        PageResponse<SaleResponse> page = service.list(null, null, null, null, 0, 20);

        assertEquals(2, page.content().get(0).batches().size());
        assertEquals(0, page.content().get(1).batches().size());
    }

    @Test
    void searchMatchesBuyerNameLotCodeAndSaleCode() {
        Root<Sale> root = deepRoot();
        Path<Object> id = root.get("id");

        CriteriaBuilder cb = applyListFilters("Kalisa", null, root);

        // Buyer name, lot code (the lot of an older sale, or a lot of a sale batches) and sale code.
        verify(cb, times(4)).like(any(), eq("%kalisa%"));
        verify(cb, never()).equal(eq(id), any(Object.class));
    }

    @Test
    void numericSearchAlsoMatchesSaleId() {
        Root<Sale> root = deepRoot();
        Path<Object> id = root.get("id");

        CriteriaBuilder cb = applyListFilters("42", null, root);

        verify(cb).equal(id, 42L);
        verify(cb, times(4)).like(any(), eq("%42%"));
    }

    @Test
    void gradeFilterUsesTheSalesGrade() {
        Root<Sale> root = deepRoot();
        Path<Object> gradeId = root.get("grade").get("id");

        CriteriaBuilder cb = applyListFilters(null, 2L, root);

        verify(cb).equal(gradeId, 2L);
        verify(cb, never()).like(any(), any(String.class));
    }

    @SuppressWarnings("unchecked")
    private CriteriaBuilder applyListFilters(String search, Long gradeId, Root<Sale> root) {
        ArgumentCaptor<Specification<Sale>> spec = ArgumentCaptor.forClass(Specification.class);
        when(f.saleRepository.findAll(spec.capture(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.list(search, null, null, gradeId, 0, 20);

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        spec.getValue().toPredicate(root, mock(CriteriaQuery.class, RETURNS_DEEP_STUBS), cb);
        return cb;
    }

    @SuppressWarnings("unchecked")
    private static Root<Sale> deepRoot() {
        return mock(Root.class, RETURNS_DEEP_STUBS);
    }

    // --- Helpers ---

    private void assertNothingSaved() {
        verify(f.saleRepository, never()).save(any());
        assertTrue(f.savedAllocations.isEmpty());
        assertTrue(f.ledger.isEmpty());
        assertTrue(f.audits.isEmpty());
    }

    private RecordedSale sell(String quantityKg, long pricePerKg) {
        return service.record(request(1L, 2L, quantityKg, pricePerKg, null, null), f.manager);
    }

    private static SaleRequest request(Long gradeId, Long buyerId, String quantityKg, long pricePerKg,
                                       List<Long> skipLotIds, String skipReason) {
        return new SaleRequest(CLIENT_UUID, gradeId, buyerId, new BigDecimal(quantityKg), pricePerKg, null,
                skipLotIds, skipReason, null);
    }
}
