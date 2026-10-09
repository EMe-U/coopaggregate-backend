package com.coopaggregate.lot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

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
}
