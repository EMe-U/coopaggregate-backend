package com.coopaggregate.sale;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/sales")
@Tag(name = "Sales", description = "Potatoes sold from closed lots")
@SecurityRequirement(name = "bearerAuth")
public class SaleController {

    private final SaleService saleService;

    public SaleController(SaleService saleService) {
        this.saleService = saleService;
    }

    @GetMapping
    @Operation(summary = "List sales, newest first (max 100 per page)")
    public PageResponse<SaleResponse> list(@RequestParam(required = false) Long lotId,
                                           @RequestParam(required = false) Long buyerId,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "20") int size) {
        return saleService.list(lotId, buyerId, page, size);
    }

    @PostMapping
    @Operation(summary = "Record a sale from a closed lot. belowFloor warns when the price is under the grade's "
            + "floor price, but the sale is still saved")
    @ApiResponse(responseCode = "201", description = "Sale recorded")
    @ApiResponse(responseCode = "200", description = "A sale with this clientUuid already exists; it is returned unchanged")
    public ResponseEntity<SaleResponse> record(@Valid @RequestBody SaleRequest request) {
        RecordedSale result = saleService.record(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.sale());
    }
}
