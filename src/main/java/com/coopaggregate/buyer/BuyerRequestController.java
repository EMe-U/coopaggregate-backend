package com.coopaggregate.buyer;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/buyer-requests")
@Tag(name = "Buyer requests", description = "Purchase requests that buyers sent from the public page")
@SecurityRequirement(name = "bearerAuth")
public class BuyerRequestController {

    private final BuyerRequestService requestService;
    private final AuthService authService;

    public BuyerRequestController(BuyerRequestService requestService, AuthService authService) {
        this.requestService = requestService;
        this.authService = authService;
    }

    @GetMapping
    @Operation(summary = "List requests, newest first (15 per page by default, max 100)")
    public PageResponse<BuyerRequestListItem> list(
            @RequestParam(required = false) BuyerRequestStatus status,
            @Parameter(description = "Part of the request code, the buyer's name or phone number")
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "15") int size) {
        return requestService.list(status, search, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "One request, with the buyer's contact details")
    public BuyerRequestDetail get(@PathVariable Long id) {
        return requestService.get(id);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Change the status (PENDING, ACCEPTED or REJECTED) and, optionally, the message the buyer "
            + "reads when they look up the request. The change is kept in the change history")
    public BuyerRequestDetail changeStatus(@PathVariable Long id, @Valid @RequestBody BuyerRequestStatusUpdate update,
                                           @AuthenticationPrincipal Jwt jwt) {
        return requestService.changeStatus(id, update, authService.currentManager(jwt.getSubject()));
    }

    @PostMapping("/{id}/buyer")
    @Operation(summary = "Turn the request into a buyer record, or link it to the buyer who already has this phone "
            + "number. Then record the sale with POST /api/sales/orders")
    public BuyerRequestDetail convertToBuyer(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return requestService.convertToBuyer(id, authService.currentManager(jwt.getSubject()));
    }
}
