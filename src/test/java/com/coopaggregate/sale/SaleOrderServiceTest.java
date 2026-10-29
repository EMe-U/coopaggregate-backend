package com.coopaggregate.sale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.lot.LotStatus;

class SaleOrderServiceTest {

    private static final UUID CLIENT_UUID = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d");

    private SaleTestFixture f;
    private SaleService service;

    @BeforeEach
    void setUp() {
        f = new SaleTestFixture();
        f.stockOfBig();
        // Grade Small: 300 kg in lot B-1.
        f.batch(7L, f.lotB1, f.bosco, "2026-10-03T08:00:00Z", "100.00");
        f.batch(8L, f.lotB1, f.alice, "2026-10-03T09:00:00Z", "200.00");
        service = f.service;
    }

    @Test
    void twoGradeOrderSavesOneSalePerGradeEachFromItsOwnOldestBatches() {
        RecordedSaleOrder result = service.recordOrder(order(
                item(1L, "120.00", 300L), item(2L, "150.00", 200L)), f.manager);

        assertTrue(result.created());
        SaleOrderResponse order = result.order();
        assertTrue(order.orderCode().matches("ORD-[2-9A-HJKMNP-Z]{4}"), order.orderCode());
        assertEquals("Kalisa Traders", order.buyer().name());
        assertEquals(2, order.sales().size());
        SaleResponse big = order.sales().get(0);
        SaleResponse small = order.sales().get(1);
        assertEquals("Big", big.gradeName());
        assertEquals(List.of(2L, 1L), big.batches().stream().map(SaleResponse.BatchLine::deliveryId).toList());
        assertEquals(36_000L, big.totalAmountRwf());
        assertEquals("Small", small.gradeName());
        assertEquals(List.of(7L, 8L), small.batches().stream().map(SaleResponse.BatchLine::deliveryId).toList());
        assertEquals(30_000L, small.totalAmountRwf());
        assertTrue(order.sales().stream().allMatch(sale -> order.orderCode().equals(sale.orderCode())));
        assertEquals(2, f.savedSales.size());
    }

    @Test
    void orderTotalsAddUpItsSales() {
        SaleOrderResponse order = service.recordOrder(order(
                item(1L, "120.50", 300L), item(2L, "79.25", 250L)), f.manager).order();

        assertEquals(new BigDecimal("199.75"), order.totalKg());
        assertEquals(36_150L + 19_813L, order.totalAmountRwf());
    }

    @Test
    void theCreditsOfEachItemAddUpToItsSaleTotal() {
        SaleOrderResponse order = service.recordOrder(order(
                item(1L, "120.50", 301L), item(2L, "79.25", 251L)), f.manager).order();

        for (SaleResponse sale : order.sales()) {
            assertEquals(sale.totalAmountRwf(), sale.batches().stream().mapToLong(SaleResponse.BatchLine::grossRwf).sum());
            assertEquals(sale.totalAmountRwf(), sale.totalDeductionRwf() + sale.totalNetRwf());
        }
        assertEquals(f.savedAllocations.size(), f.credits().size());
    }

    @Test
    void eachItemIsRecordedInLedgerWithItsCredits() {
        SaleOrderResponse order = service.recordOrder(order(
                item(1L, "120.00", 300L), item(2L, "150.00", 200L)), f.manager).order();

        List<SaleTestFixture.Ledgered> sales = f.ledger.stream()
                .filter(entry -> entry.type() == com.coopaggregate.ledger.LedgerEntryType.SALE).toList();
        assertEquals(2, sales.size());
        assertEquals("Sale " + order.sales().get(0).saleCode() + " of Big to Kalisa Traders", sales.get(0).reason());
        assertEquals("Sale " + order.sales().get(1).saleCode() + " of Small to Kalisa Traders", sales.get(1).reason());
        assertEquals(4, f.credits().size());
        assertEquals(f.savedAllocations.stream().map(SaleAllocation::getId).toList(),
                f.credits().stream().map(SaleTestFixture.Ledgered::relatedId).toList());
    }

    @Test
    void locksAreTakenGradesInIdOrderThenLotsThenBatchesThenLedger() {
        // The request lists Small first.
        service.recordOrder(order(item(2L, "150.00", 200L), item(1L, "120.00", 300L)), f.manager);

        List<String> locks = f.calls.subList(0, 7);
        assertEquals(List.of("grade:1", "grade:2", "lot:3", "lot:4", "lot:5", "batches", "batches"), locks);
        int firstWrite = f.calls.indexOf("sale");
        assertEquals(7, firstWrite, "nothing is written before everything is locked and planned");
        // Each sale writes its allocations before its own ledger entries.
        assertTrue(f.calls.indexOf("ledger:SALE") > f.calls.indexOf("allocation"));
        assertEquals(List.of("sale", "allocation", "allocation", "ledger:SALE", "ledger:SALE_CREDIT",
                "ledger:SALE_CREDIT", "sale", "allocation", "allocation", "ledger:SALE", "ledger:SALE_CREDIT",
                "ledger:SALE_CREDIT"), f.calls.subList(7, f.calls.size()));
    }

    @Test
    void lotsThatReachZeroBecomeSold() {
        service.recordOrder(order(item(1L, "250.00", 300L), item(2L, "100.00", 250L)), f.manager);

        assertEquals(LotStatus.SOLD, f.lotA1.getStatus());
        assertEquals(LotStatus.SOLD, f.lotA2.getStatus());
        assertEquals(LotStatus.OPEN, f.lotB1.getStatus());
    }

    @Test
    void tooMuchInSecondItemSavesNothing() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.recordOrder(order(
                item(1L, "120.00", 300L), item(2L, "300.01", 250L)), f.manager));

        assertEquals("Only 300.00 kg of Small can be sold now, but 300.01 kg were asked for.", e.getMessage());
        assertNothingSaved();
        f.deliveries.forEach(batch -> assertEquals(BigDecimal.ZERO, batch.getSoldKg()));
        assertEquals(LotStatus.CLOSED, f.lotA1.getStatus());
    }

    @Test
    void aPriceAtTheDeductionInOneItemSavesNothing() {
        assertThrows(BadRequestException.class, () -> service.recordOrder(order(
                item(1L, "120.00", 300L), item(2L, "10.00", 5L)), f.manager));

        assertNothingSaved();
    }

    @Test
    void unknownGradeSavesNothing() {
        assertEquals("Grade 9 does not exist.", assertThrows(BadRequestException.class, () -> service.recordOrder(
                order(item(1L, "100.00", 300L), item(9L, "100.00", 250L)), f.manager)).getMessage());
        assertNothingSaved();
    }

    @Test
    void sameGradeTwiceIsRejected() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.recordOrder(order(
                item(1L, "100.00", 300L), item(1L, "50.00", 300L)), f.manager));

        assertEquals("Each grade can appear only once in an order.", e.getMessage());
        verify(f.gradeRepository, never()).findForUpdateById(anyLong());
        assertNothingSaved();
    }

    @Test
    void aSkipInOneItemIsRecordedWithTheSaleCode() {
        SaleOrderRequest request = new SaleOrderRequest(CLIENT_UUID, 2L, null, List.of(
                item(1L, "100.00", 300L),
                new SaleOrderRequest.Item(2L, new BigDecimal("150.00"), 200L, List.of(7L),
                        "The buyer wants the second delivery")));

        SaleOrderResponse order = service.recordOrder(request, f.manager).order();

        assertEquals(List.of(8L), order.sales().get(1).batches().stream()
                .map(SaleResponse.BatchLine::deliveryId).toList());
        assertEquals(1, f.audits.size());
        assertEquals(AuditAction.SALE_FIFO_SKIPPED, f.audits.get(0).getAction());
        assertEquals("Sale " + order.sales().get(1).saleCode() + ": skipped RCT-0007. Reason: The buyer wants the "
                + "second delivery", f.audits.get(0).getDetails());
    }

    @Test
    void aSkipWithoutAReasonSavesNothingAndLocksNothing() {
        SaleOrderRequest request = new SaleOrderRequest(CLIENT_UUID, 2L, null, List.of(
                item(1L, "100.00", 300L),
                new SaleOrderRequest.Item(2L, new BigDecimal("150.00"), 200L, List.of(7L), "short")));

        assertThrows(BadRequestException.class, () -> service.recordOrder(request, f.manager));

        assertTrue(f.calls.isEmpty());
        assertNothingSaved();
    }

    @Test
    void sameClientUuidReturnsExistingOrder() {
        SaleOrderResponse first = service.recordOrder(order(
                item(1L, "120.00", 300L), item(2L, "150.00", 200L)), f.manager).order();
        f.calls.clear();
        f.ledger.clear();
        when(f.saleOrderRepository.findByClientUuid(CLIENT_UUID)).thenReturn(Optional.of(f.savedOrders.get(0)));

        RecordedSaleOrder again = service.recordOrder(order(
                item(1L, "120.00", 300L), item(2L, "150.00", 200L)), f.manager);

        assertFalse(again.created());
        assertEquals(first.orderCode(), again.order().orderCode());
        assertEquals(first.totalAmountRwf(), again.order().totalAmountRwf());
        assertEquals(first.sales().get(0).batches(), again.order().sales().get(0).batches());
        assertEquals(1, f.savedOrders.size());
        assertEquals(2, f.savedSales.size());
        assertTrue(f.ledger.isEmpty());
        assertEquals(List.of("grade:1", "grade:2"), f.calls);
    }

    private void assertNothingSaved() {
        verify(f.saleOrderRepository, never()).save(any());
        verify(f.saleRepository, never()).save(any());
        assertTrue(f.savedAllocations.isEmpty());
        assertTrue(f.ledger.isEmpty());
        assertTrue(f.audits.isEmpty());
    }

    private static SaleOrderRequest order(SaleOrderRequest.Item... items) {
        return new SaleOrderRequest(CLIENT_UUID, 2L, null, List.of(items));
    }

    private static SaleOrderRequest.Item item(Long gradeId, String quantityKg, long pricePerKg) {
        return new SaleOrderRequest.Item(gradeId, new BigDecimal(quantityKg), pricePerKg, null, null);
    }
}
