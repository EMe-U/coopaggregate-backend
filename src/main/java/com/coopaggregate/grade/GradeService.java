package com.coopaggregate.grade;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.audit.ChangeList;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.EntityNotFoundException;

@Service
public class GradeService {

    private final GradeRepository gradeRepository;
    private final AuditLogRepository auditLogRepository;

    public GradeService(GradeRepository gradeRepository, AuditLogRepository auditLogRepository) {
        this.gradeRepository = gradeRepository;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public List<GradeResponse> list(boolean includeInactive) {
        List<Grade> grades = includeInactive
                ? gradeRepository.findAll(Sort.by("code"))
                : gradeRepository.findByActiveTrueOrderByCodeAsc();
        return grades.stream().map(GradeResponse::from).toList();
    }

    /** Saves the grade and writes the fields that changed to the change history. */
    @Transactional
    public GradeResponse update(Long id, GradeRequest request, Manager manager) {
        // Locked like a delivery does, so a delivery never reads a half-saved grade.
        Grade grade = gradeRepository.findForUpdateById(id)
                .orElseThrow(() -> new EntityNotFoundException("Grade " + id + " not found."));
        String name = request.name().trim();
        String nameRw = blankToNull(request.nameRw());
        String description = blankToNull(request.description());
        String sizeInfo = blankToNull(request.sizeInfo());
        String packaging = blankToNull(request.packaging());

        ChangeList changes = new ChangeList()
                .add("name", grade.getName(), name)
                .add("nameRw", grade.getNameRw(), nameRw)
                .add("active", grade.isActive(), request.active())
                .add("floorPricePerKg", grade.getFloorPricePerKg(), request.floorPricePerKg())
                .add("description", grade.getDescription(), description)
                .add("sizeInfo", grade.getSizeDescription(), sizeInfo)
                .add("packaging", grade.getPackaging(), packaging);

        grade.setName(name);
        grade.setNameRw(nameRw);
        grade.setActive(request.active());
        grade.setFloorPricePerKg(request.floorPricePerKg());
        grade.setDescription(description);
        grade.setSizeDescription(sizeInfo);
        grade.setPackaging(packaging);

        if (!changes.isEmpty()) {
            auditLogRepository.save(AuditLog.of(AuditAction.GRADE_UPDATED, null, manager,
                    "Grade " + grade.getCode() + ": " + changes));
        }
        return GradeResponse.from(grade);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
