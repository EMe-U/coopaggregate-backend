package com.coopaggregate.delivery;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.auth.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/deliveries")
@Tag(name = "Deliveries", description = "Potatoes delivered by members. Mistakes are fixed with a ledger reversal")
@SecurityRequirement(name = "bearerAuth")
public class DeliveryController {

    private final DeliveryService deliveryService;
    private final AuthService authService;

    public DeliveryController(DeliveryService deliveryService, AuthService authService) {
        this.deliveryService = deliveryService;
        this.authService = authService;
    }

    @PostMapping
    @Operation(summary = "Record a delivery. It goes to the open lot of its grade, gets a receipt code "
            + "and is added to the ledger")
    @ApiResponse(responseCode = "201", description = "Delivery recorded")
    @ApiResponse(responseCode = "200", description = "A delivery with this clientUuid already exists; it is returned unchanged")
    public ResponseEntity<DeliveryResponse> record(@Valid @RequestBody DeliveryRequest request,
                                                   @AuthenticationPrincipal Jwt jwt) {
        RecordedDelivery result = deliveryService.record(request, authService.currentManager(jwt.getSubject()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.delivery());
    }
}
