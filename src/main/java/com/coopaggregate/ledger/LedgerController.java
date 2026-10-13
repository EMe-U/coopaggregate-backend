package com.coopaggregate.ledger;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.auth.AuthService;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.manager.Manager;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/ledger")
@Tag(name = "Ledger", description = "Append-only record of all deliveries, sales, shares and payments")
@SecurityRequirement(name = "bearerAuth")
public class LedgerController {

    private final LedgerService ledgerService;
    private final LedgerQueryService ledgerQueryService;
    private final AuthService authService;

    public LedgerController(LedgerService ledgerService, LedgerQueryService ledgerQueryService,
                            AuthService authService) {
        this.ledgerService = ledgerService;
        this.ledgerQueryService = ledgerQueryService;
        this.authService = authService;
    }

    @GetMapping
    @Operation(summary = "List ledger entries, newest first (max 100 per page). All filters are optional")
    public PageResponse<LedgerEntryResponse> list(
            @RequestParam(required = false) LedgerEntryType type,
            @RequestParam(required = false) Long memberId,
            @RequestParam(required = false) Long lotId,
            @Parameter(description = "First day, inclusive (yyyy-MM-dd, Kigali time)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Last day, inclusive (yyyy-MM-dd, Kigali time)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Part of a reference code (RCT-, SAL-, ORD-, PAY-) or a member name")
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ledgerQueryService.list(new LedgerFilter(type, memberId, lotId, from, to, search), page, size);
    }

    @GetMapping("/verify")
    @Operation(summary = "Recompute every hash and check that the ledger has not been changed")
    public VerifyResult verify() {
        return ledgerService.verify();
    }

    @PostMapping("/{id}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Correct a mistake by adding a REVERSAL entry that cancels the given entry")
    public LedgerEntryResponse reverse(@PathVariable Long id,
                                       @Valid @RequestBody ReverseRequest request,
                                       @AuthenticationPrincipal Jwt jwt) {
        Manager manager = authService.currentManager(jwt.getSubject());
        LedgerEntry reversal = ledgerService.reverse(id, request.reason(), manager);
        return LedgerEntryResponse.from(reversal, null);
    }
}
