package com.coopaggregate.grade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class GradeServiceTest {

    @Test
    void gradesIncludeFloorPriceOrNull() {
        GradeRepository repository = mock(GradeRepository.class);
        when(repository.findByActiveTrueOrderByCodeAsc()).thenReturn(List.of(
                grade(1L, "A", "Big", 300L),
                grade(2L, "B", "Normal", null)));

        List<GradeResponse> grades = new GradeService(repository).listActive();

        assertEquals(List.of(
                new GradeResponse(1L, "A", "Big", 300L),
                new GradeResponse(2L, "B", "Normal", null)), grades);
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
