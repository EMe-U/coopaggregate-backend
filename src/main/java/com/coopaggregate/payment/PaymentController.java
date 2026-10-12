package com.coopaggregate.payment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/payments")
@Tag(name = "Payments", description = "Money paid to members from their lot shares")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping("/owed")
    @Operation(summary = "Members who are still owed money from shared lots, largest amount first")
    public OwedMembersResponse owed(
            @Parameter(description = "Part of the name, member number or phone number (case-insensitive)")
            @RequestParam(required = false) String search) {
        return paymentService.owedMembers(search);
    }
}
