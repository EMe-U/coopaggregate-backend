package com.coopaggregate.lot;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/lots")
@Tag(name = "Lots", description = "Fixed-size lots of one grade, filled by the system with confirmed deliveries in "
        + "the order they are confirmed. There is no manual opening or closing")
@SecurityRequirement(name = "bearerAuth")
public class LotController {

    private final LotService lotService;

    public LotController(LotService lotService) {
        this.lotService = lotService;
    }

    @GetMapping
    @Operation(summary = "List lots, newest first (max 100 per page), with delivered, lost, sold and remaining kg")
    public PageResponse<LotResponse> list(@RequestParam(required = false) LotStatus status,
                                          @RequestParam(required = false) Long gradeId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return lotService.list(status, gradeId, page, size);
    }

    @GetMapping("/stock")
    @Operation(summary = "Stock balance: kg left in total and per grade, read from the batches and split into "
            + "sellable (confirmed), waiting (pending) and disputed kg, all-time delivered, sold and lost kg, and "
            + "today's deliveries (Kigali time). Only sellable kg can be sold")
    public StockResponse stock() {
        return lotService.stock();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one lot with the kilograms each member delivered to it")
    public LotDetailResponse get(@PathVariable Long id) {
        return lotService.get(id);
    }

    @GetMapping("/open")
    @Operation(summary = "Get the lot being filled for a grade, with the kg in it so far (totalKg) and its capacity")
    @ApiResponse(responseCode = "200", description = "The lot being filled")
    @ApiResponse(responseCode = "204", description = "No lot is being filled; the next confirmed delivery of this grade "
            + "starts one")
    @ApiResponse(responseCode = "404", description = "The grade does not exist")
    public ResponseEntity<OpenLotResponse> findOpenLot(@RequestParam Long gradeId) {
        return lotService.findOpenLot(gradeId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}
