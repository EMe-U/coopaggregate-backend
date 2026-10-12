package com.coopaggregate.payment;

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
@RequestMapping("/api/payments")
@Tag(name = "Payments", description = "Money paid to members from their lot shares")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final PaymentService paymentService;
    private final AuthService authService;

    public PaymentController(PaymentService paymentService, AuthService authService) {
        this.paymentService = paymentService;
        this.authService = authService;
    }

    @GetMapping
    @Operation(summary = "List payments, newest first (max 100 per page)")
    public PageResponse<PaymentResponse> list(@RequestParam(required = false) Long memberId,
                                              @RequestParam(required = false) PaymentMethod method,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return paymentService.list(memberId, method, page, size);
    }

    @GetMapping("/owed")
    @Operation(summary = "Members who are still owed money from shared lots, largest amount first")
    public OwedMembersResponse owed(
            @Parameter(description = "Part of the name, member number or phone number (case-insensitive)")
            @RequestParam(required = false) String search) {
        return paymentService.owedMembers(search);
    }

    @PostMapping
    @Operation(summary = "Pay a member. The amount cannot be more than they are owed; MOMO needs a reference")
    @ApiResponse(responseCode = "201", description = "Payment recorded")
    @ApiResponse(responseCode = "200",
            description = "A payment with this clientUuid already exists; it is returned unchanged")
    public ResponseEntity<PaymentResponse> record(@Valid @RequestBody PaymentRequest request,
                                                  @AuthenticationPrincipal Jwt jwt) {
        RecordedPayment result = paymentService.record(request, authService.currentManager(jwt.getSubject()));
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.payment());
    }
}
