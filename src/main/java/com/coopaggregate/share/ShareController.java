package com.coopaggregate.share;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.auth.AuthService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/lots/{lotId}")
@Tag(name = "Sharing", description = "Share a sold lot's money between the members who delivered to it")
@SecurityRequirement(name = "bearerAuth")
public class ShareController {

    private final SharingService sharingService;
    private final AuthService authService;

    public ShareController(SharingService sharingService, AuthService authService) {
        this.sharingService = sharingService;
        this.authService = authService;
    }

    @GetMapping("/share-preview")
    @Operation(summary = "Show how a sold lot's money would be shared, without saving anything")
    public SharePreviewResponse preview(@PathVariable Long lotId) {
        return sharingService.preview(lotId);
    }

    @PostMapping("/share")
    @Operation(summary = "Share a sold lot: save each member's share and mark the lot SHARED. Can only be done "
            + "once, and not while the lot has disputed deliveries (409 lists them)")
    public SharePreviewResponse share(@PathVariable Long lotId, @AuthenticationPrincipal Jwt jwt) {
        return sharingService.share(lotId, authService.currentManager(jwt.getSubject()));
    }
}
