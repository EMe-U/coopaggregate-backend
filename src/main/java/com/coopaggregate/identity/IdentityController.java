package com.coopaggregate.identity;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.auth.AuthService;
import com.coopaggregate.error.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/members/verify")
@Tag(name = "Identity verification", description = "Check a new member's identity before registration")
@SecurityRequirement(name = "bearerAuth")
public class IdentityController {

    private final IdentityVerificationService verificationService;
    private final AuthService authService;

    public IdentityController(IdentityVerificationService verificationService, AuthService authService) {
        this.verificationService = verificationService;
        this.authService = authService;
    }

    @PostMapping("/start")
    @Operation(summary = "Start a verification: returns where to send the member and a one-use state, valid for "
            + "10 minutes. With the mock provider authorizationUrl is null")
    public StartVerificationResponse start(@AuthenticationPrincipal Jwt jwt) {
        return verificationService.start(authService.currentManager(jwt.getSubject()));
    }

    @PostMapping("/callback")
    @Operation(summary = "Complete a verification with the code and state the provider sent back. 409 if the "
            + "person is already a member or the state was already used")
    public VerifyResultResponse callback(@Valid @RequestBody VerifyCallbackRequest request,
                                         @AuthenticationPrincipal Jwt jwt) {
        return verificationService.complete(request.code(), request.state(),
                authService.currentManager(jwt.getSubject()));
    }

    @ExceptionHandler(IdentityProviderException.class)
    public ResponseEntity<ApiError> handleProvider(IdentityProviderException e) {
        HttpStatus status = e.isNotConfigured() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(new ApiError(e.getMessage()));
    }
}
