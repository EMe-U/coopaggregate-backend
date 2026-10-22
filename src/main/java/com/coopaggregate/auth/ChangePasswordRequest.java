package com.coopaggregate.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// BCrypt only uses the first 72 bytes, so longer passwords are refused instead of silently cut.
public record ChangePasswordRequest(
        @NotBlank String currentPassword,
        @NotBlank
        @Size(min = 8, message = "must be at least 8 characters")
        @Size(max = 72, message = "must be at most 72 characters")
        String newPassword) {
}
