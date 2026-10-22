package com.coopaggregate.lot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
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
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;

import jakarta.persistence.EntityNotFoundException;

class LotServiceTest {

    private LotRepository lotRepository;
    private GradeRepository gradeRepository;
    private LotService service;

    @BeforeEach
    void setUp() {
        lotRepository = mock(LotRepository.class);
        gradeRepository = mock(GradeRepository.class);
        service = new LotService(lotRepository, gradeRepository);
        when(gradeRepository.existsById(1L)).thenReturn(true);
    }

    @Test
    void openLotIsReturnedWithTotalKg() {
        Grade grade = new Grade();
        grade.setName("Big");
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", 3L);
        lot.setLotCode("A-2026-01");
        lot.setGrade(grade);
        when(lotRepository.findFirstByGradeIdAndStatus(1L, LotStatus.OPEN)).thenReturn(Optional.of(lot));
        when(lotRepository.totalDeliveredKg(3L)).thenReturn(new BigDecimal("1250.50"));

        OpenLotResponse response = service.findOpenLot(1L).orElseThrow();

        assertEquals(new OpenLotResponse(3L, "A-2026-01", "Big", new BigDecimal("1250.50")), response);
    }

    @Test
    void noOpenLotGivesEmptyResult() {
        when(lotRepository.findFirstByGradeIdAndStatus(1L, LotStatus.OPEN)).thenReturn(Optional.empty());

        assertTrue(service.findOpenLot(1L).isEmpty());
    }

    @Test
    void unknownGradeIsNotFound() {
        EntityNotFoundException e = assertThrows(EntityNotFoundException.class, () -> service.findOpenLot(9L));

        assertEquals("Grade 9 does not exist.", e.getMessage());
    }

    @Test
    void openLotCanBeClosed() {
        Lot lot = lot(3L, "A-2026-01", "A", "Big", LotStatus.OPEN);
        givenLot(lot);

        LotResponse response = service.close(3L);

        assertEquals(LotStatus.CLOSED, lot.getStatus());
        assertEquals(KigaliTime.today(), lot.getCloseDate());
        assertEquals(LotStatus.CLOSED, response.status());
        assertEquals(KigaliTime.today(), response.closedAt());
        verify(gradeRepository).findForUpdateById(1L);
    }

    @Test
    void closedLotCannotBeClosedAgain() {
        givenLot(lot(3L, "A-2026-01", "A", "Big", LotStatus.CLOSED));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.close(3L));

        assertEquals("Lot A-2026-01 is CLOSED. Only an open lot can be closed.", e.getMessage());
    }

    @Test
    void soldLotCannotBeClosed() {
        givenLot(lot(3L, "A-2026-01", "A", "Big", LotStatus.SOLD));

        assertThrows(IllegalStateException.class, () -> service.close(3L));
    }

    @Test
    void closingUnknownLotIsNotFound() {
        when(lotRepository.findGradeIdById(99L)).thenReturn(Optional.empty());

        EntityNotFoundException e = assertThrows(EntityNotFoundException.class, () -> service.close(99L));

        assertEquals("Lot 99 does not exist.", e.getMessage());
    }

    @Test
    void lotDetailHasComputedQuantitiesAndMembers() {
        givenLot(lot(3L, "A-2026-01", "A", "Big", LotStatus.CLOSED));
        when(lotRepository.findQuantities(anyCollection())).thenReturn(List.of(new LotQuantities(3L,
                new BigDecimal("1000.00"), new BigDecimal("25.50"), new BigDecimal("400.00"), 120000L, 2L,
                new BigDecimal("150.00"))));
        List<LotMemberResponse> members = List.of(
                new LotMemberResponse(5L, "Uwimana Claudine", "M-0005", new BigDecimal("600.00")),
                new LotMemberResponse(6L, "Habimana Eric", "M-0006", new BigDecimal("400.00")));
        when(lotRepository.findMembers(3L)).thenReturn(members);

        LotDetailResponse detail = service.get(3L);

        // Pending kg are shown next to the totals but stay in them: they are still physical stock.
        assertEquals(new BigDecimal("1000.00"), detail.deliveredKg());
        assertEquals(new BigDecimal("574.50"), detail.remainingKg());
        assertEquals(new BigDecimal("150.00"), detail.pendingConfirmationKg());
        assertEquals(120000L, detail.saleAmountRwf());
        assertEquals(2L, detail.memberCount());
        assertEquals(members, detail.members());
    }

    @Test
    void stockAddsRemainingKgOfOpenAndClosedLotsPerGrade() {
        Lot openA = lot(3L, "A-2026-02", "A", "Big", LotStatus.OPEN);
        Lot closedA = lot(4L, "A-2026-01", "A", "Big", LotStatus.CLOSED);
        Lot closedB = lot(5L, "B-2026-01", "B", "Normal", LotStatus.CLOSED);
        when(lotRepository.findByStatusIn(List.of(LotStatus.OPEN, LotStatus.CLOSED)))
                .thenReturn(List.of(openA, closedB, closedA));
        when(lotRepository.findQuantities(anyCollection())).thenReturn(List.of(
                new LotQuantities(3L, new BigDecimal("200.00"), null, null, null, null),
                new LotQuantities(4L, new BigDecimal("1000.00"), new BigDecimal("50.00"), new BigDecimal("900.00"),
                        null, null),
                new LotQuantities(5L, new BigDecimal("300.00"), null, null, null, null)));

        when(lotRepository.totalDeliveredKg()).thenReturn(new BigDecimal("4000.00"));
        when(lotRepository.totalSoldKg()).thenReturn(new BigDecimal("3300.00"));
        when(lotRepository.totalLostKg()).thenReturn(new BigDecimal("150.00"));
        when(lotRepository.totalPendingConfirmationKg()).thenReturn(new BigDecimal("120.50"));
        when(lotRepository.deliveredKgBetween(any(), any())).thenReturn(new BigDecimal("200.00"));
        when(lotRepository.deliveryCountBetween(any(), any())).thenReturn(3L);

        StockResponse stock = service.stock();

        assertEquals(new BigDecimal("550.00"), stock.totalRemainingKg());
        assertEquals(new BigDecimal("4000.00"), stock.totalDeliveredKg());
        assertEquals(new BigDecimal("3300.00"), stock.totalSoldKg());
        assertEquals(new BigDecimal("150.00"), stock.totalLostKg());
        assertEquals(new BigDecimal("120.50"), stock.pendingConfirmationKg());
        assertEquals(new BigDecimal("200.00"), stock.addedTodayKg());
        assertEquals(3L, stock.deliveriesToday());
        assertEquals(1, stock.openLots());
        assertEquals(2, stock.closedLots());
        assertEquals(List.of(
                new StockResponse.GradeStock("Big", new BigDecimal("250.00")),
                new StockResponse.GradeStock("Normal", new BigDecimal("300.00"))), stock.byGrade());
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
