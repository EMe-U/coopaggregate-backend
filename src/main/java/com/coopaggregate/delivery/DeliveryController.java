package com.coopaggregate.delivery;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    @GetMapping
    @Operation(summary = "List deliveries, newest first (max 100 per page)")
    public PageResponse<DeliveryResponse> list(
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long lotId,
            @Parameter(description = "First day, inclusive (yyyy-MM-dd, Kigali time)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last day, inclusive (yyyy-MM-dd, Kigali time)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return deliveryService.list(memberId, lotId, from, to, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one delivery")
    public DeliveryResponse get(@PathVariable Long id) {
        return deliveryService.get(id);
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
