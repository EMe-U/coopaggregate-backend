package com.coopaggregate.sale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.auth.AuthService;
import com.coopaggregate.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sales")
@Tag(name = "Sales", description = "Potatoes sold by grade, from the oldest confirmed batches")
@SecurityRequirement(name = "bearerAuth")
public class SaleController {

    private final SaleService saleService;
    private final AuthService authService;

    public SaleController(SaleService saleService, AuthService authService) {
        this.saleService = saleService;
        this.authService = authService;
    }

    @GetMapping
    @Operation(summary = "List sales, newest first (max 100 per page)")
    public PageResponse<SaleResponse> list(
            @Parameter(description = "Part of the buyer name, lot code or sale code, or the sale id (case-insensitive)")
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Long lotId,
            @RequestParam(required = false) Long buyerId,
            @RequestParam(required = false) Long gradeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return saleService.list(search, lotId, buyerId, gradeId, page, size);
    }

    @GetMapping("/summary")
    @Operation(summary = "This month's sales (Kigali time): amount, kg, number of buyers and average price per kg")
    public SaleSummaryResponse summary() {
        return saleService.summary();
    }

    @PostMapping
    @Operation(summary = "Record a sale of a grade. The kg are taken from the oldest confirmed batches of the grade, "
            + "whatever their lot, and each batch's member is credited (their part of the sale amount minus the "
            + "deduction per kg). Preview it first with GET /api/grades/{gradeId}/sale-preview. 400 when there are "
            + "not enough sellable kg or the price is not more than the deduction; nothing is saved then. "
            + "belowFloor warns when the price is under the grade's floor price, but the sale is still saved")
    @ApiResponse(responseCode = "201", description = "Sale recorded")
    @ApiResponse(responseCode = "200", description = "A sale with this clientUuid already exists; it is returned unchanged")
    public ResponseEntity<SaleResponse> record(@Valid @RequestBody SaleRequest request,
                                               @AuthenticationPrincipal Jwt jwt) {
        RecordedSale result = saleService.record(request, authService.currentManager(jwt.getSubject()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.sale());
    }

    @PostMapping("/orders")
    @Operation(summary = "Record one buyer order for several grades (each grade once). Each grade gets its own sale, "
            + "taken from its oldest confirmed batches; if any item is invalid, nothing is saved")
    @ApiResponse(responseCode = "201", description = "Order recorded")
    @ApiResponse(responseCode = "200",
            description = "An order with this clientUuid already exists; it is returned unchanged")
    public ResponseEntity<SaleOrderResponse> recordOrder(@Valid @RequestBody SaleOrderRequest request,
                                                         @AuthenticationPrincipal Jwt jwt) {
        RecordedSaleOrder result = saleService.recordOrder(request, authService.currentManager(jwt.getSubject()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
    }
}
