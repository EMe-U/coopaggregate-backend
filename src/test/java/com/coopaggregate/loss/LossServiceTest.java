package com.coopaggregate.loss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.batch.BatchAllocator;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotQuantities;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.EntityNotFoundException;

class LossServiceTest {

    private BatchAllocator batchAllocator;

    private LossRepository lossRepository;
    private LedgerService ledgerService;
    private Lot lot;
    private Manager manager;
    private LossService service;

    @BeforeEach
    void setUp() {
        lossRepository = mock(LossRepository.class);
        LotRepository lotRepository = mock(LotRepository.class);
        LotService lotService = mock(LotService.class);
        ledgerService = mock(LedgerService.class);

        lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", 3L);
        lot.setLotCode("A-2026-01");
        lot.setStatus(LotStatus.CLOSED);
        when(lotRepository.findForUpdateById(3L)).thenReturn(Optional.of(lot));
        when(lotRepository.findForUpdateById(99L)).thenReturn(Optional.empty());
        // 1000 delivered, 25.50 lost, 400 sold: 574.50 kg remain.
        when(lotService.quantitiesOf(lot)).thenReturn(new LotQuantities(3L, new BigDecimal("1000.00"),
                new BigDecimal("25.50"), new BigDecimal("400.00"), 120000L, 4L));
        when(lossRepository.save(any(Loss.class))).thenAnswer(invocation -> {
            Loss loss = invocation.getArgument(0);
            ReflectionTestUtils.setField(loss, "id", 8L);
            return loss;
        });

        manager = new Manager();
        batchAllocator = mock(BatchAllocator.class);
        service = new LossService(lossRepository, lotRepository, lotService, ledgerService, batchAllocator);
    }

    @Test
    void lossIsRecordedOnClosedLot() {
        LossResponse response = service.record(3L, request("30.00", null), manager);

        assertEquals(8L, response.id());
        assertEquals("A-2026-01", response.lotCode());
        assertEquals(new BigDecimal("30.00"), response.quantityKg());
        assertEquals(LossReason.ROT, response.reason());
        assertEquals(KigaliTime.today(), response.recordedAt());
    }

    @Test
    void lossTakesItsKgFromTheLotsBatches() {
        service.record(3L, request("30.00", null), manager);

        ArgumentCaptor<Loss> loss = ArgumentCaptor.forClass(Loss.class);
        verify(batchAllocator).allocateLoss(loss.capture());
        assertEquals(new BigDecimal("30.00"), loss.getValue().getQuantityKg());
    }

    @Test
    void lossIsAllowedOnOpenLot() {
        lot.setStatus(LotStatus.OPEN);

        service.record(3L, request("30.00", null), manager);

        verify(lossRepository).save(any(Loss.class));
    }

    @Test
    void lossOfAllRemainingKgIsAllowed() {
        service.record(3L, request("574.50", null), manager);

        verify(lossRepository).save(any(Loss.class));
    }

    @Test
    void lossMoreThanRemainingIsRejected() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.record(3L, request("574.51", null), manager));

        assertEquals("Only 574.50 kg remain in lot A-2026-01.", e.getMessage());
        verify(lossRepository, never()).save(any());
        verify(ledgerService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void lossOnSoldLotIsRejected() {
        lot.setStatus(LotStatus.SOLD);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.record(3L, request("10.00", null), manager));

        assertEquals("Lot A-2026-01 is SOLD. Losses can only be recorded on open or closed lots.", e.getMessage());
    }

    @Test
    void lossOnUnknownLotIsNotFound() {
        assertThrows(EntityNotFoundException.class, () -> service.record(99L, request("10.00", null), manager));
    }

    @Test
    void recordedAtFromRequestIsKept() {
        LossResponse response = service.record(3L, request("10.00", LocalDate.of(2026, 10, 1)), manager);

        assertEquals(LocalDate.of(2026, 10, 1), response.recordedAt());
    }

    @Test
    void lossIsRecordedInLedger() {
        service.record(3L, request("30.00", null), manager);

        verify(ledgerService).record(LedgerEntryType.LOSS, "LOSS", 8L, new BigDecimal("30.00"), null,
                "Loss from A-2026-01: ROT", manager);
    }

    @Test
    void dryingLossKeepsReasonAndNotes() {
        LossResponse response = service.record(3L,
                new LossRequest(new BigDecimal("12.00"), LossReason.DRYING, null, "  Weight lost in store  "), manager);

        assertEquals(LossReason.DRYING, response.reason());
        assertEquals("Weight lost in store", response.notes());
        verify(ledgerService).record(LedgerEntryType.LOSS, "LOSS", 8L, new BigDecimal("12.00"), null,
                "Loss from A-2026-01: DRYING", manager);
    }

    @Test
    void blankNotesAreStoredAsNull() {
        LossResponse response = service.record(3L,
                new LossRequest(new BigDecimal("12.00"), LossReason.OTHER, null, " "), manager);

        assertNull(response.notes());
    }

    private static LossRequest request(String quantityKg, LocalDate recordedAt) {
        return new LossRequest(new BigDecimal(quantityKg), LossReason.ROT, recordedAt, "Wet store room");
    }
}
