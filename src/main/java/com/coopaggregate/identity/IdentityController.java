package com.coopaggregate.identity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/members/verify")
@Tag(name = "Identity verification", description = "Check a new member's identity before registration")
@SecurityRequirement(name = "bearerAuth")
public class IdentityController {

    private final IdentityAvailability identityAvailability;

    public IdentityController(IdentityAvailability identityAvailability) {
        this.identityAvailability = identityAvailability;
    }

    @GetMapping("/status")
    @Operation(summary = "Whether identity verification is available (switched on and set up) and whether the "
            + "cooperative requires it to register a member")
    public IdentityStatusResponse status() {
        return identityAvailability.status();
    }
}
