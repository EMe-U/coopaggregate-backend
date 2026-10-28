package com.coopaggregate.sale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.batch.BatchAllocator;
import com.coopaggregate.buyer.Buyer;
import com.coopaggregate.buyer.BuyerRepository;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotQuantities;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;

class SaleOrderServiceTest {

    private static final UUID CLIENT_UUID = UUID.fromString("9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d");

    private SaleRepository saleRepository;
    private SaleOrderRepository saleOrderRepository;
    private LotRepository lotRepository;
    private LedgerService ledgerService;
    private Lot lotA;
    private Lot lotB;
    private Manager manager;
    private SaleService service;

    @BeforeEach
    void setUp() {
        saleRepository = mock(SaleRepository.class);
        saleOrderRepository = mock(SaleOrderRepository.class);
        lotRepository = mock(LotRepository.class);
        BuyerRepository buyerRepository = mock(BuyerRepository.class);
        LotService lotService = mock(LotService.class);
        ledgerService = mock(LedgerService.class);

        // Lot A has 500 kg left, lot B has 300 kg left.
        lotA = lot(3L, "A-2026-01");
        lotB = lot(4L, "B-2026-01");
        when(lotRepository.findForUpdateById(3L)).thenReturn(Optional.of(lotA));
        when(lotRepository.findForUpdateById(4L)).thenReturn(Optional.of(lotB));
        when(lotService.quantitiesOf(lotA)).thenReturn(new LotQuantities(3L, new BigDecimal("500.00"),
                null, null, null, null));
        when(lotService.quantitiesOf(lotB)).thenReturn(new LotQuantities(4L, new BigDecimal("300.00"),
                null, null, null, null));

        Buyer buyer = new Buyer();
        ReflectionTestUtils.setField(buyer, "id", 2L);
        buyer.setName("Kalisa Traders");
        buyer.setPhone("+250788123456");
        when(buyerRepository.findById(2L)).thenReturn(Optional.of(buyer));

        when(saleOrderRepository.findByClientUuid(any())).thenReturn(Optional.empty());
        when(saleOrderRepository.save(any(SaleOrder.class))).thenAnswer(invocation -> {
            SaleOrder order = invocation.getArgument(0);
            ReflectionTestUtils.setField(order, "id", 50L);
            return order;
        });
        AtomicLong saleIds = new AtomicLong(10);
        when(saleRepository.save(any(Sale.class))).thenAnswer(invocation -> {
            Sale sale = invocation.getArgument(0);
            ReflectionTestUtils.setField(sale, "id", saleIds.incrementAndGet());
            return sale;
        });

        manager = new Manager();
        service = new SaleService(saleRepository, saleOrderRepository, lotRepository, buyerRepository, lotService,
                ledgerService, mock(BatchAllocator.class));
    }

    @Test
    void twoLotOrderSavesOneSalePerLot() {
        RecordedSaleOrder result = service.recordOrder(order(
                item(3L, "500.00", 300L), item(4L, "100.00", 250L)), manager);

        assertTrue(result.created());
        SaleOrderResponse order = result.order();
        assertTrue(order.orderCode().matches("ORD-[2-9A-HJKMNP-Z]{4}"), order.orderCode());
        assertEquals("Kalisa Traders", order.buyer().name());
        assertEquals(2, order.sales().size());
        assertEquals("A-2026-01", order.sales().get(0).lotCode());
        assertEquals(150_000L, order.sales().get(0).totalAmountRwf());
        assertEquals("B-2026-01", order.sales().get(1).lotCode());
        assertEquals(25_000L, order.sales().get(1).totalAmountRwf());
        assertTrue(order.sales().stream().allMatch(sale -> order.orderCode().equals(sale.orderCode())));
        verify(saleRepository, times(2)).save(any(Sale.class));
    }

    @Test
    void orderTotalsAddUpItsSales() {
        SaleOrderResponse order = service.recordOrder(order(
                item(3L, "120.50", 300L), item(4L, "79.25", 250L)), manager).order();

        assertEquals(new BigDecimal("199.75"), order.totalKg());
        assertEquals(36_150L + 19_813L, order.totalAmountRwf());
    }

    @Test
    void lotsThatReachZeroBecomeSold() {
        service.recordOrder(order(item(3L, "500.00", 300L), item(4L, "100.00", 250L)), manager);

        assertEquals(LotStatus.SOLD, lotA.getStatus());
        assertEquals(LotStatus.CLOSED, lotB.getStatus());
    }

    @Test
    void eachItemIsRecordedInLedger() {
        SaleOrderResponse order = service.recordOrder(order(
                item(3L, "500.00", 300L), item(4L, "100.00", 250L)), manager).order();

        verify(ledgerService).record(LedgerEntryType.SALE, "SALE", 11L, new BigDecimal("500.00"), 150_000L,
                "Sale " + order.sales().get(0).saleCode() + " from A-2026-01 to Kalisa Traders", manager);
        verify(ledgerService).record(LedgerEntryType.SALE, "SALE", 12L, new BigDecimal("100.00"), 25_000L,
                "Sale " + order.sales().get(1).saleCode() + " from B-2026-01 to Kalisa Traders", manager);
    }

    @Test
    void tooMuchInSecondItemSavesNothing() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.recordOrder(order(
                item(3L, "500.00", 300L), item(4L, "300.01", 250L)), manager));

        assertEquals("Only 300.00 kg remain in lot B-2026-01.", e.getMessage());
        assertNothingSaved();
        assertEquals(LotStatus.CLOSED, lotA.getStatus());
    }

    @Test
    void openLotInSecondItemSavesNothing() {
        lotB.setStatus(LotStatus.OPEN);

        BadRequestException e = assertThrows(BadRequestException.class, () -> service.recordOrder(order(
                item(3L, "100.00", 300L), item(4L, "100.00", 250L)), manager));

        assertEquals("Lot B-2026-01 is OPEN. Only a closed lot can be sold.", e.getMessage());
        assertNothingSaved();
    }

    @Test
    void unknownLotSavesNothing() {
        when(lotRepository.findForUpdateById(9L)).thenReturn(Optional.empty());

        assertThrows(BadRequestException.class, () -> service.recordOrder(order(
                item(3L, "100.00", 300L), item(9L, "100.00", 250L)), manager));
        assertNothingSaved();
    }

    @Test
    void sameLotTwiceIsRejected() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.recordOrder(order(
                item(3L, "100.00", 300L), item(3L, "50.00", 300L)), manager));

        assertEquals("Each lot can appear only once in an order.", e.getMessage());
        verify(lotRepository, never()).findForUpdateById(anyLong());
        assertNothingSaved();
    }

    @Test
    void sameClientUuidReturnsExistingOrder() {
        SaleOrder existing = new SaleOrder();
        ReflectionTestUtils.setField(existing, "id", 50L);
        existing.setOrderCode("ORD-7K2Q");
        Buyer buyer = new Buyer();
        buyer.setName("Kalisa Traders");
        existing.setBuyer(buyer);
        Sale sale = new Sale();
        sale.setSaleOrder(existing);
        sale.setLot(lotA);
        sale.setBuyer(buyer);
        sale.setQuantityKg(new BigDecimal("500.00"));
        sale.setTotalAmount(150_000L);
        when(saleOrderRepository.findByClientUuid(CLIENT_UUID)).thenReturn(Optional.of(existing));
        when(saleRepository.findBySaleOrderIdOrderByIdAsc(50L)).thenReturn(List.of(sale));
        lotA.setStatus(LotStatus.SOLD);

        RecordedSaleOrder result = service.recordOrder(order(item(3L, "500.00", 300L)), manager);

        assertFalse(result.created());
        assertEquals("ORD-7K2Q", result.order().orderCode());
        assertEquals(150_000L, result.order().totalAmountRwf());
        assertNothingSaved();
    }

    @Test
    void lotsAreLockedInIdOrder() {
        service.recordOrder(order(item(4L, "100.00", 250L), item(3L, "100.00", 300L)), manager);

        InOrder order = inOrder(lotRepository);
        order.verify(lotRepository).findForUpdateById(3L);
        order.verify(lotRepository).findForUpdateById(4L);
    }

    private void assertNothingSaved() {
        verify(saleOrderRepository, never()).save(any());
        verify(saleRepository, never()).save(any());
        verify(ledgerService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    private static SaleOrderRequest order(SaleOrderRequest.Item... items) {
        return new SaleOrderRequest(CLIENT_UUID, 2L, null, List.of(items));
    }

    private static SaleOrderRequest.Item item(Long lotId, String quantityKg, long pricePerKg) {
        return new SaleOrderRequest.Item(lotId, new BigDecimal(quantityKg), pricePerKg);
    }

    private static Lot lot(Long id, String code) {
        Grade grade = new Grade();
        grade.setName("Big");
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", id);
        lot.setLotCode(code);
        lot.setGrade(grade);
        lot.setStatus(LotStatus.CLOSED);
        return lot;
    }
}
