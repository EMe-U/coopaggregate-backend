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
@Tag(name = "Lots", description = "Lots collect the deliveries of one grade until they are sold")
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

    @GetMapping("/{id}")
    @Operation(summary = "Get one lot with the kilograms each member delivered to it")
    public LotDetailResponse get(@PathVariable Long id) {
        return lotService.get(id);
    }

    @GetMapping("/open")
    @Operation(summary = "Get the open lot for a grade with the kilograms delivered to it so far")
    @ApiResponse(responseCode = "200", description = "The open lot")
    @ApiResponse(responseCode = "204", description = "No lot is open; one is opened by the next delivery of this grade")
    @ApiResponse(responseCode = "404", description = "The grade does not exist")
    public ResponseEntity<OpenLotResponse> findOpenLot(@RequestParam Long gradeId) {
        return lotService.findOpenLot(gradeId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }
}
