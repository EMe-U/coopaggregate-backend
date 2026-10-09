package com.coopaggregate.delivery;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.auth.AuthService;

import io.swagger.v3.oas.annotations.Operation;
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
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a delivery. It goes to the open lot of its grade, gets a receipt code "
            + "and is added to the ledger")
    public DeliveryResponse record(@Valid @RequestBody DeliveryRequest request, @AuthenticationPrincipal Jwt jwt) {
        return deliveryService.record(request, authService.currentManager(jwt.getSubject()));
    }
}
