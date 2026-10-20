package com.coopaggregate.setting;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SettingsRequest(
        @NotNull(message = "is required")
        Boolean smsEnabled,

        @Email(message = "must be a valid email address")
        @Size(max = 150, message = "must be at most 150 characters")
        String cooperativeEmail,

        @Size(max = 150, message = "must be at most 150 characters")
        String location,

        @NotNull(message = "is required")
        @Positive(message = "must be more than 0")
        @Digits(integer = 10, fraction = 2, message = "must have at most 2 decimals")
        BigDecimal minOrderKg,

        @NotNull(message = "is required (use [] for none)")
        @Size(max = 10, message = "can have at most 10 emails")
        List<@NotBlank(message = "must not be empty") @Email(message = "must be a valid email address")
                @Size(max = 150, message = "must be at most 150 characters") String> committeeEmails) {
}
