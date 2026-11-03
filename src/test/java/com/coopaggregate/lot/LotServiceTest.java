package com.coopaggregate.lot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;

import jakarta.persistence.EntityNotFoundException;

class LotServiceTest {

    private LotRepository lotRepository;
    private LotPortionRepository lotPortionRepository;
    private GradeRepository gradeRepository;
    private LotService service;

    @BeforeEach
    void setUp() {
        lotRepository = mock(LotRepository.class);
        lotPortionRepository = mock(LotPortionRepository.class);
        gradeRepository = mock(GradeRepository.class);
        service = new LotService(lotRepository, lotPortionRepository, gradeRepository);
        when(gradeRepository.existsById(1L)).thenReturn(true);
    }

    @Test
    void theLotBeingFilledIsReturnedWithItsKgAndCapacity() {
        Grade grade = new Grade();
        grade.setName("Big");
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", 3L);
        lot.setLotCode("A-2026-01");
        lot.setGrade(grade);
        lot.setCapacityKg(new BigDecimal("100.00"));
        when(lotRepository.findFirstByGradeIdAndStatus(1L, LotStatus.FILLING)).thenReturn(Optional.of(lot));
        when(lotPortionRepository.filledKg(3L)).thenReturn(new BigDecimal("72.50"));

        OpenLotResponse response = service.findOpenLot(1L).orElseThrow();

        assertEquals(new OpenLotResponse(3L, "A-2026-01", "Big", new BigDecimal("72.50"), new BigDecimal("100.00")),
                response);
    }

    @Test
    void noLotBeingFilledGivesEmptyResult() {
        when(lotRepository.findFirstByGradeIdAndStatus(1L, LotStatus.FILLING)).thenReturn(Optional.empty());

        assertTrue(service.findOpenLot(1L).isEmpty());
    }

    @Test
    void anOldOpenLotIsNotTheLotBeingFilled() {
        when(lotRepository.findFirstByGradeIdAndStatus(1L, LotStatus.FILLING)).thenReturn(Optional.empty());

        assertTrue(service.findOpenLot(1L).isEmpty());
        verify(lotRepository, never()).findFirstByGradeIdAndStatus(1L, LotStatus.OPEN);
    }

    @Test
    void unknownGradeIsNotFound() {
        EntityNotFoundException e = assertThrows(EntityNotFoundException.class, () -> service.findOpenLot(9L));

        assertEquals("Grade 9 does not exist.", e.getMessage());
    }

    @Test
    void lotDetailHasComputedQuantitiesAndMembers() {
        givenLot(lot(3L, "A-2026-01", "A", "Big", LotStatus.CLOSED));
        when(lotRepository.findQuantities(anyCollection())).thenReturn(List.of(new LotQuantities(3L,
                new BigDecimal("1000.00"), new BigDecimal("25.50"), new BigDecimal("400.00"), 120000L, 2L,
                new BigDecimal("150.00"))));
        List<LotMemberResponse> members = List.of(
                new LotMemberResponse(5L, "Uwimana Claudine", "M-0005", new BigDecimal("600.00"),
                        new BigDecimal("240.00"), new BigDecimal("15.30"), new BigDecimal("344.70")),
                new LotMemberResponse(6L, "Habimana Eric", "M-0006", new BigDecimal("400.00"),
                        new BigDecimal("160.00"), new BigDecimal("10.20"), new BigDecimal("229.80")));
        when(lotRepository.findMembers(3L)).thenReturn(members);

        LotDetailResponse detail = service.get(3L);

        // Pending kg are shown next to the totals but stay in them: they are still physical stock.
        assertEquals(new BigDecimal("1000.00"), detail.deliveredKg());
        assertEquals(new BigDecimal("574.50"), detail.remainingKg());
        assertEquals(new BigDecimal("150.00"), detail.pendingConfirmationKg());
        assertEquals(120000L, detail.saleAmountRwf());
        assertEquals(2L, detail.memberCount());
        assertEquals(members, detail.members());
        // A lot from before fixed-size lots has no capacity.
        assertTrue(detail.olderLot());
        assertEquals(null, detail.capacityKg());
        assertEquals(BigDecimal.ZERO, detail.shortfallKg());
        assertEquals(BigDecimal.ZERO, detail.spaceLeftKg());
    }

    @Test
    void aLotBeingFilledShowsItsSpaceLeft() {
        Lot lot = lot(3L, "A-2026-04", "A", "Big", LotStatus.FILLING);
        lot.setCapacityKg(new BigDecimal("100.00"));
        givenLot(lot);
        when(lotRepository.findQuantities(anyCollection())).thenReturn(List.of(new LotQuantities(3L,
                new BigDecimal("72.50"), null, null, null, 3L)));

        LotDetailResponse detail = service.get(3L);

        assertFalse(detail.olderLot());
        assertEquals(new BigDecimal("100.00"), detail.capacityKg());
        assertEquals(new BigDecimal("27.50"), detail.spaceLeftKg());
        assertEquals(BigDecimal.ZERO, detail.shortfallKg());
    }

    @Test
    void aFullLotThatLostADeliveryToAReversalShowsItsShortfall() {
        for (LotStatus status : new LotStatus[] {LotStatus.FULL, LotStatus.SOLD}) {
            Lot lot = lot(3L, "A-2026-03", "A", "Big", status);
            lot.setCapacityKg(new BigDecimal("100.00"));
            givenLot(lot);
            when(lotRepository.findQuantities(anyCollection())).thenReturn(List.of(new LotQuantities(3L,
                    new BigDecimal("80.00"), null, null, null, 2L)));

            LotDetailResponse detail = service.get(3L);

            assertEquals(new BigDecimal("20.00"), detail.shortfallKg(), status.name());
            assertEquals(BigDecimal.ZERO, detail.spaceLeftKg(), status.name());
        }
    }

    @Test
    void aFullLotWithAllItsKgHasNoShortfall() {
        Lot lot = lot(3L, "A-2026-03", "A", "Big", LotStatus.FULL);
        lot.setCapacityKg(new BigDecimal("100.00"));
        givenLot(lot);
        when(lotRepository.findQuantities(anyCollection())).thenReturn(List.of(new LotQuantities(3L,
                new BigDecimal("100.00"), null, null, null, 2L)));

        assertEquals(0, service.get(3L).shortfallKg().signum());
    }

    @Test
    void stockIsReadPerGradeFromTheBatchesAndSplitBySellableWaitingAndDisputedKg() {
        Lot openA = lot(3L, "A-2026-02", "A", "Big", LotStatus.OPEN);
        Lot closedA = lot(4L, "A-2026-01", "A", "Big", LotStatus.CLOSED);
        Lot closedB = lot(5L, "B-2026-01", "B", "Normal", LotStatus.CLOSED);
        when(lotRepository.findByStatusIn(any()))
                .thenReturn(List.of(openA, closedB, closedA));
        // Lots of the same grade are added together; a status with no kg left is simply not there.
        when(lotRepository.findKgLeftByGradeAndStatus()).thenReturn(List.of(
                new GradeKgLeft("B", "Normal", ConfirmationStatus.CONFIRMED, new BigDecimal("300.00")),
                new GradeKgLeft("A", "Big", ConfirmationStatus.CONFIRMED, new BigDecimal("200.00")),
                new GradeKgLeft("A", "Big", ConfirmationStatus.PENDING, new BigDecimal("40.00")),
                new GradeKgLeft("A", "Big", ConfirmationStatus.DISPUTED, new BigDecimal("10.00")),
                new GradeKgLeft("B", "Normal", ConfirmationStatus.PENDING, new BigDecimal("25.50"))));

        when(lotRepository.totalDeliveredKg()).thenReturn(new BigDecimal("4000.00"));
        when(lotRepository.totalSoldKg()).thenReturn(new BigDecimal("3300.00"));
        when(lotRepository.totalLostKg()).thenReturn(new BigDecimal("150.00"));
        when(lotRepository.totalPendingConfirmationKg()).thenReturn(new BigDecimal("120.50"));
        when(lotRepository.deliveredKgBetween(any(), any())).thenReturn(new BigDecimal("200.00"));
        when(lotRepository.deliveryCountBetween(any(), any())).thenReturn(3L);

        StockResponse stock = service.stock();

        assertEquals(new BigDecimal("575.50"), stock.totalRemainingKg());
        assertEquals(new BigDecimal("500.00"), stock.totalSellableKg());
        assertEquals(new BigDecimal("65.50"), stock.totalWaitingKg());
        assertEquals(new BigDecimal("10.00"), stock.totalDisputedKg());
        assertEquals(new BigDecimal("4000.00"), stock.totalDeliveredKg());
        assertEquals(new BigDecimal("3300.00"), stock.totalSoldKg());
        assertEquals(new BigDecimal("150.00"), stock.totalLostKg());
        assertEquals(new BigDecimal("120.50"), stock.pendingConfirmationKg());
        assertEquals(new BigDecimal("200.00"), stock.addedTodayKg());
        assertEquals(3L, stock.deliveriesToday());
        assertEquals(1, stock.openLots());
        assertEquals(2, stock.closedLots());
        // In grade code order. remainingKg is the three parts added up.
        assertEquals(List.of(
                new StockResponse.GradeStock("Big", new BigDecimal("250.00"), new BigDecimal("200.00"),
                        new BigDecimal("40.00"), new BigDecimal("10.00")),
                new StockResponse.GradeStock("Normal", new BigDecimal("325.50"), new BigDecimal("300.00"),
                        new BigDecimal("25.50"), BigDecimal.ZERO)), stock.byGrade());
    }

    @Test
    void stockDoesNotNeedALotToBeClosed() {
        // The kg of an open lot are sellable stock like those of a closed lot: only confirmation matters.
        when(lotRepository.findByStatusIn(any()))
                .thenReturn(List.of(lot(3L, "A-2026-02", "A", "Big", LotStatus.OPEN)));
        when(lotRepository.findKgLeftByGradeAndStatus()).thenReturn(List.of(
                new GradeKgLeft("A", "Big", ConfirmationStatus.CONFIRMED, new BigDecimal("120.00"))));

        StockResponse stock = service.stock();

        assertEquals(new BigDecimal("120.00"), stock.totalSellableKg());
        assertEquals(1, stock.openLots());
        assertEquals(0, stock.closedLots());
    }

    @Test
    void aGradeWithNoKgLeftIsNotListed() {
        when(lotRepository.findByStatusIn(any())).thenReturn(List.of());
        when(lotRepository.findKgLeftByGradeAndStatus()).thenReturn(List.of());

        StockResponse stock = service.stock();

        assertEquals(List.of(), stock.byGrade());
        assertEquals(BigDecimal.ZERO, stock.totalRemainingKg());
        assertEquals(BigDecimal.ZERO, stock.totalSellableKg());
        assertEquals(BigDecimal.ZERO, stock.totalWaitingKg());
        assertEquals(BigDecimal.ZERO, stock.totalDisputedKg());
    }

    private void givenLot(Lot lot) {
        when(lotRepository.findGradeIdById(lot.getId())).thenReturn(Optional.of(lot.getGrade().getId()));
        when(lotRepository.findById(lot.getId())).thenReturn(Optional.of(lot));
    }

    private static Lot lot(Long id, String code, String gradeCode, String gradeName, LotStatus status) {
        Grade grade = new Grade();
        ReflectionTestUtils.setField(grade, "id", gradeCode.equals("A") ? 1L : 2L);
        grade.setCode(gradeCode);
        grade.setName(gradeName);
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", id);
        lot.setLotCode(code);
        lot.setGrade(grade);
        lot.setStatus(status);
        return lot;
    }

    @Test
    void todayMeansTheKigaliCalendarDay() {
        when(lotRepository.findByStatusIn(any())).thenReturn(List.of());

        service.stock();

        LocalDate today = KigaliTime.today();
        verify(lotRepository).deliveredKgBetween(today.atStartOfDay(KigaliTime.ZONE).toInstant(),
                today.plusDays(1).atStartOfDay(KigaliTime.ZONE).toInstant());
    }
}
