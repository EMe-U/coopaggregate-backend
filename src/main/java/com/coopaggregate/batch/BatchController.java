package com.coopaggregate.batch;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Batches", description = "Each delivery as a batch: kg received, sold, lost and remaining. Read-only")
@SecurityRequirement(name = "bearerAuth")
public class BatchController {

    private final BatchService batchService;

    public BatchController(BatchService batchService) {
        this.batchService = batchService;
    }

    @GetMapping("/api/members/{memberId}/batches")
    @Operation(summary = "A member's batches, newest first (max 100 per page), with totals over all of them. "
            + "Reversed deliveries are left out")
    public MemberBatchesResponse memberBatches(
            @PathVariable Long memberId,
            @Parameter(description = "all (default) or unsold (only batches with kg left)")
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return batchService.memberBatches(memberId, status, page, size);
    }

    @GetMapping("/api/grades/{gradeId}/batches")
    @Operation(summary = "A grade's batches that still have kg, oldest first: the order sales and losses take kg in "
            + "within a lot (max 100 per page)")
    public GradeBatchesResponse gradeBatches(
            @PathVariable Long gradeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return batchService.gradeBatches(gradeId, page, size);
    }
}
