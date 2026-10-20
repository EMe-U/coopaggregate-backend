package com.coopaggregate.setting;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.audit.AuditEntryResponse;
import com.coopaggregate.audit.AuditService;
import com.coopaggregate.auth.AuthService;
import com.coopaggregate.common.PageResponse;

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
    private final AuditService auditService;
    private final AuthService authService;

    public SettingController(SettingService settingService, AuditService auditService, AuthService authService) {
        this.settingService = settingService;
        this.auditService = auditService;
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

    @GetMapping("/history")
    @Operation(summary = "Change history, newest first (max 100 per page): settings, grades, password changes and "
            + "member PINs")
    public PageResponse<AuditEntryResponse> history(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return auditService.history(page, size);
    }
}
