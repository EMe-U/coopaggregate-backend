package com.coopaggregate.grade;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GradeService {

    private final GradeRepository gradeRepository;

    public GradeService(GradeRepository gradeRepository) {
        this.gradeRepository = gradeRepository;
    }

    @Transactional(readOnly = true)
    public List<GradeResponse> listActive() {
        return gradeRepository.findByActiveTrueOrderByCodeAsc().stream()
                .map(GradeResponse::from)
                .toList();
    }
}
