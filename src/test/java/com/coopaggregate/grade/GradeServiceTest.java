package com.coopaggregate.grade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.EntityNotFoundException;

class GradeServiceTest {

    private GradeRepository gradeRepository;
    private AuditLogRepository auditLogRepository;
    private GradeService gradeService;

    @BeforeEach
    void setUp() {
        gradeRepository = mock(GradeRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        gradeService = new GradeService(gradeRepository, auditLogRepository);
    }

    @Test
    void gradesIncludeFloorPriceOrNull() {
        when(gradeRepository.findByActiveTrueOrderByCodeAsc()).thenReturn(List.of(
                grade(1L, "A", "Big", 300L),
                grade(2L, "B", "Normal", null)));

        List<GradeResponse> grades = gradeService.list(false);

        assertEquals(List.of(
                new GradeResponse(1L, "A", "Big", null, true, 300L, null, null, null),
                new GradeResponse(2L, "B", "Normal", null, true, null, null, null, null)), grades);
    }

    @Test
    void inactiveGradesAreListedWhenAsked() {
        Grade small = grade(3L, "C", "Small", null);
        small.setActive(false);
        when(gradeRepository.findAll(Sort.by("code"))).thenReturn(List.of(grade(1L, "A", "Big", null), small));

        List<GradeResponse> grades = gradeService.list(true);

        assertEquals(2, grades.size());
        assertFalse(grades.get(1).active());
    }

    @Test
    void updateSavesTheFieldsAndRecordsWhatChanged() {
        Grade big = grade(1L, "A", "Big", null);
        when(gradeRepository.findForUpdateById(1L)).thenReturn(Optional.of(big));
        Manager manager = new Manager();

        GradeResponse response = gradeService.update(1L, new GradeRequest(" Big ", "Binini", true, 350L,
                "Large potatoes for restaurants", " ", "50 kg bags"), manager);

        assertEquals("Binini", response.nameRw());
        assertEquals(350L, response.floorPricePerKg());
        assertEquals("Large potatoes for restaurants", response.description());
        assertNull(response.sizeInfo());
        assertEquals("50 kg bags", response.packaging());
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals(AuditAction.GRADE_UPDATED, audit.getValue().getAction());
        assertEquals("Grade A: nameRw: (empty) -> Binini; floorPricePerKg: (empty) -> 350; "
                + "description: (empty) -> Large potatoes for restaurants; packaging: (empty) -> 50 kg bags",
                audit.getValue().getDetails());
    }

    @Test
    void savingWithoutChangesWritesNoHistory() {
        when(gradeRepository.findForUpdateById(1L)).thenReturn(Optional.of(grade(1L, "A", "Big", 300L)));

        gradeService.update(1L, new GradeRequest("Big", null, true, 300L, null, null, null), new Manager());

        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void unknownGradeIsNotFound() {
        when(gradeRepository.findForUpdateById(9L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> gradeService.update(9L,
                new GradeRequest("Big", null, true, null, null, null, null), new Manager()));
    }

    private static Grade grade(Long id, String code, String name, Long floorPricePerKg) {
        Grade grade = new Grade();
        ReflectionTestUtils.setField(grade, "id", id);
        grade.setCode(code);
        grade.setName(name);
        grade.setFloorPricePerKg(floorPricePerKg);
        return grade;
    }
}
