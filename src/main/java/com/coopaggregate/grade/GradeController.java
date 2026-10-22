package com.coopaggregate.grade;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.auth.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/grades")
@Tag(name = "Grades", description = "Potato grades A, B and C. They are fixed: grades can be edited but not "
        + "created or deleted")
@SecurityRequirement(name = "bearerAuth")
public class GradeController {

    private final GradeService gradeService;
    private final AuthService authService;

    public GradeController(GradeService gradeService, AuthService authService) {
        this.gradeService = gradeService;
        this.authService = authService;
    }

    @GetMapping
    @Operation(summary = "List grades sorted by code, only active ones unless includeInactive=true. "
            + "floorPricePerKg is null when not set")
    public List<GradeResponse> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return gradeService.list(includeInactive);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit a grade. The change is kept in the change history")
    public GradeResponse update(@PathVariable Long id, @Valid @RequestBody GradeRequest request,
                                @AuthenticationPrincipal Jwt jwt) {
        return gradeService.update(id, request, authService.currentManager(jwt.getSubject()));
    }
}
