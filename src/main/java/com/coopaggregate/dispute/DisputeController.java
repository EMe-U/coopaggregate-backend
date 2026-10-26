package com.coopaggregate.dispute;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/disputes")
@Tag(name = "Disputes", description = "Deliveries members say are wrong. Settle them with resolve-dispute, correct "
        + "or reverse on the delivery")
@SecurityRequirement(name = "bearerAuth")
public class DisputeController {

    private final DisputeService disputeService;

    public DisputeController(DisputeService disputeService) {
        this.disputeService = disputeService;
    }

    @GetMapping
    @Operation(summary = "Disputed deliveries, newest first (15 per page by default, max 100), with totals for the page "
            + "header: open disputes, kg affected and settled disputes")
    public DisputesResponse list(
            @Parameter(description = "open (default) or settled")
            @RequestParam(required = false) String status,
            @Parameter(description = "Part of the receipt code, member name or member number")
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        return disputeService.list(status, search, page, size);
    }
}
