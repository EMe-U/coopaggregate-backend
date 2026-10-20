package com.coopaggregate.setting;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.auth.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/settings")
@Tag(name = "Settings", description = "Cooperative settings. Every change is kept in the change history")
@SecurityRequirement(name = "bearerAuth")
public class SettingController {

    private final SettingService settingService;
    private final AuthService authService;

    public SettingController(SettingService settingService, AuthService authService) {
        this.settingService = settingService;
        this.authService = authService;
    }

    @GetMapping
    @Operation(summary = "Get the settings")
    public SettingsResponse get() {
        return settingService.get();
    }

    @PutMapping
    @Operation(summary = "Save the settings. committeeEmails: at most 10 valid emails")
    public SettingsResponse update(@Valid @RequestBody SettingsRequest request, @AuthenticationPrincipal Jwt jwt) {
        return settingService.update(request, authService.currentManager(jwt.getSubject()));
    }
}
