package com.coopaggregate.payment;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Members")
@SecurityRequirement(name = "bearerAuth")
public class StatementController {

    private final StatementService statementService;

    public StatementController(StatementService statementService) {
        this.statementService = statementService;
    }

    @GetMapping("/api/members/{memberId}/statement")
    @Operation(summary = "A member's money: shares, deductions, payments and what is still owed, "
            + "plus their lots that are not shared yet")
    public StatementResponse statement(@PathVariable Long memberId) {
        return statementService.statement(memberId);
    }
}
