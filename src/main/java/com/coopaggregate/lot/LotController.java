package com.coopaggregate.lot;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
