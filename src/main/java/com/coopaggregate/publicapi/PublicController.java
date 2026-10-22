package com.coopaggregate.publicapi;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The only endpoints that work without logging in. Nothing here may show members, stock, prices or the ledger. */
@RestController
@RequestMapping("/api/public")
@Tag(name = "Public", description = "For buyers, no login. Grades, cooperative contact details and purchase requests")
public class PublicController {

    private final PublicService publicService;

    public PublicController(PublicService publicService) {
        this.publicService = publicService;
    }

    @GetMapping("/grades")
    @Operation(summary = "Active grades with their description, size and packaging. No prices")
    public List<PublicGradeResponse> grades() {
        return publicService.grades();
    }

    @GetMapping("/info")
    @Operation(summary = "Cooperative name, location, email and the smallest order in kg")
    public PublicInfoResponse info() {
        return publicService.info();
    }
}
