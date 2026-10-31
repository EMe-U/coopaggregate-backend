package com.coopaggregate.identity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.auth.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/members/verify")
@Tag(name = "Identity verification", description = "Check a new member's identity before registration")
@SecurityRequirement(name = "bearerAuth")
public class IdentityController {

    private final IdentityAvailability identityAvailability;
    private final IdentityVerificationService verificationService;
    private final AuthService authService;

    public IdentityController(IdentityAvailability identityAvailability,
                              IdentityVerificationService verificationService, AuthService authService) {
        this.identityAvailability = identityAvailability;
        this.verificationService = verificationService;
        this.authService = authService;
    }

    @GetMapping("/status")
    @Operation(summary = "Whether identity verification is available (switched on and set up) and whether the "
            + "cooperative requires it to register a member")
    public IdentityStatusResponse status() {
        return identityAvailability.status();
    }

    @PostMapping("/start")
    @Operation(summary = "Start a verification: returns eSignet's login page (authorizationUrl) to send the member "
            + "to, and a one-use state valid for 10 minutes. 503 if verification is not available")
    public StartVerificationResponse start(@AuthenticationPrincipal Jwt jwt) {
        return verificationService.start(authService.currentManager(jwt.getSubject()));
    }

    @PostMapping("/callback")
    @Operation(summary = "Complete a verification with the code and state eSignet sent back to the redirect page. "
            + "400 if the state is unknown or expired, 409 if it was already used or the person is already a member, "
            + "502 if eSignet failed or its answer could not be trusted, 503 if verification is not available")
    public VerifyResultResponse callback(@Valid @RequestBody VerifyCallbackRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return verificationService.complete(request.code(), request.state(),
                authService.currentManager(jwt.getSubject()));
    }
}
