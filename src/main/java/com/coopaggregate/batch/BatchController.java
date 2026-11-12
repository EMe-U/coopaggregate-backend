package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.error.BadRequestException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Batches", description = "Each delivery as a batch: kg received, sold, lost and remaining. Read-only")
@SecurityRequirement(name = "bearerAuth")
public class BatchController {

    private final BatchService batchService;
    private final SalePreviewService salePreviewService;

    public BatchController(BatchService batchService, SalePreviewService salePreviewService) {
        this.batchService = batchService;
        this.salePreviewService = salePreviewService;
    }

    @GetMapping("/api/grades/{gradeId}/sale-preview")
    @Operation(summary = "Which portions a sale of this grade would use, first in first out by lot (older lots "
            + "first, then fixed-size lots in fill order, each shared in proportion to what its portions have left), "
            + "and what each member would be credited. Only confirmed kg are sold. Nothing is saved; when there are "
            + "not enough kg, enough is false and message says why")
    public SalePreviewResponse salePreview(
            @PathVariable Long gradeId,
            @RequestParam(required = false) BigDecimal quantityKg,
            @RequestParam(required = false) Long pricePerKg,
            @Parameter(description = "No longer accepted: lots cannot be skipped. Any id is a 400")
            @RequestParam(required = false) List<String> skipLots,
            @Parameter(description = "No longer accepted: batches cannot be skipped. Any id is a 400")
            @RequestParam(required = false) List<String> skip) {
        if (asksToSkip(skipLots) || asksToSkip(skip)) {
            throw new BadRequestException(SalePlanner.NO_SKIPPING);
        }
        return salePreviewService.preview(gradeId, quantityKg, pricePerKg);
    }

    // An empty value (skipLots=) asks for nothing; any id, even one that is not a number, asks to skip.
    private static boolean asksToSkip(List<String> ids) {
        return ids != null && ids.stream().anyMatch(id -> id != null && !id.isBlank());
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
