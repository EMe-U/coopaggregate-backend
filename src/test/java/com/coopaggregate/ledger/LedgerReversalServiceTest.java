package com.coopaggregate.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryService;
import com.coopaggregate.loss.Loss;
import com.coopaggregate.loss.LossRepository;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.payment.PaymentService;
import com.coopaggregate.payment.ProducePayment;
import com.coopaggregate.sale.Sale;
import com.coopaggregate.sale.SaleRepository;

class LedgerReversalServiceTest {

    private LedgerEntryRepository ledgerEntryRepository;
    private DeliveryService deliveryService;
    private PaymentService paymentService;
    private SaleRepository saleRepository;
    private LossRepository lossRepository;
    private Manager manager;
    private LedgerReversalService service;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository = mock(LedgerEntryRepository.class);
        deliveryService = mock(DeliveryService.class);
        paymentService = mock(PaymentService.class);
        saleRepository = mock(SaleRepository.class);
        lossRepository = mock(LossRepository.class);
        manager = new Manager();
        service = new LedgerReversalService(ledgerEntryRepository, deliveryService, paymentService, saleRepository,
                lossRepository);
    }

    @Test
    void deliveryEntryIsReversedThroughTheDelivery() {
        givenEntry(1L, LedgerEntryType.DELIVERY, 42L);
        LedgerEntry reversal = new LedgerEntry();
        Delivery delivery = new Delivery();
        delivery.markReversed(reversal);
        when(deliveryService.reverse(42L, "Weighed twice", manager)).thenReturn(delivery);

        assertSame(reversal, service.reverse(1L, "Weighed twice", manager));
    }

    @Test
    void paymentEntryIsReversedThroughThePayment() {
        givenEntry(2L, LedgerEntryType.PAYMENT, 9L);
        LedgerEntry reversal = new LedgerEntry();
        ProducePayment payment = new ProducePayment();
        payment.markReversed(reversal);
        when(paymentService.reverse(9L, "Paid twice", manager)).thenReturn(payment);

        assertSame(reversal, service.reverse(2L, "Paid twice", manager));
    }

    @Test
    void shareEntryCannotBeReversed() {
        givenEntry(3L, LedgerEntryType.SHARE, 500L);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reverse(3L, "Undo", manager));

        assertEquals("Shares cannot be reversed: the lot's money has already been divided between its members.",
                e.getMessage());
    }

    @Test
    void saleCreditEntryCannotBeReversedOnItsOwn() {
        givenEntry(5L, LedgerEntryType.SALE_CREDIT, 600L);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reverse(5L, "Undo", manager));

        assertEquals("A member's credit from a sale cannot be reversed on its own.", e.getMessage());
    }

    @Test
    void saleEntryCannotBeReversedAfterSharing() {
        givenEntry(4L, LedgerEntryType.SALE, 400L);
        Sale sale = new Sale();
        sale.setLot(lot(LotStatus.SHARED));
        when(saleRepository.findById(400L)).thenReturn(Optional.of(sale));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reverse(4L, "Undo", manager));

        assertEquals("The sale's lot has already been shared, so the sale can no longer be reversed.",
                e.getMessage());
    }

    @Test
    void lossEntryCannotBeReversedAfterASale() {
        givenEntry(5L, LedgerEntryType.LOSS, 300L);
        Loss loss = new Loss();
        loss.setLot(lot(LotStatus.CLOSED));
        when(lossRepository.findById(300L)).thenReturn(Optional.of(loss));
        when(saleRepository.existsByLotId(3L)).thenReturn(true);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reverse(5L, "Undo", manager));

        assertEquals("The loss's lot already has sales, so the loss can no longer be reversed.", e.getMessage());
    }

    @Test
    void reversalEntryCannotBeReversed() {
        givenEntry(6L, LedgerEntryType.REVERSAL, 42L);

        assertThrows(IllegalStateException.class, () -> service.reverse(6L, "Undo", manager));
        verify(deliveryService, never()).reverse(any(), any(), any());
    }

    @Test
    void unknownEntryIsNotFound() {
        when(ledgerEntryRepository.findById(99L)).thenReturn(Optional.empty());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.reverse(99L, "Undo", manager));

        assertEquals("Ledger entry 99 does not exist.", e.getMessage());
    }

    private void givenEntry(Long id, LedgerEntryType type, Long recordId) {
        LedgerEntry entry = new LedgerEntry();
        ReflectionTestUtils.setField(entry, "id", id);
        entry.setEntryType(type);
        entry.setRelatedEntityId(recordId);
        when(ledgerEntryRepository.findById(id)).thenReturn(Optional.of(entry));
    }

    private static Lot lot(LotStatus status) {
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", 3L);
        lot.setStatus(status);
        return lot;
    }
}
