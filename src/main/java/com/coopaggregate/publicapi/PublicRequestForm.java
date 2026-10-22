package com.coopaggregate.publicapi;

import java.math.BigDecimal;

import com.coopaggregate.common.RwandanPhoneNumber;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * A purchase request from the public page. `website` is a honeypot: it is hidden on the page, so only a bot fills
 * it in. The kilograms must reach the cooperative's minimum order, which is checked against the settings.
 */
public record PublicRequestForm(
        @NotBlank(message = "is required")
        @Size(max = 150, message = "must be at most 150 characters")
        String name,

        @NotBlank(message = "is required")
        @Pattern(regexp = RwandanPhoneNumber.PATTERN,
                message = "must be a Rwandan mobile number: 07XXXXXXXX, 2507XXXXXXXX or +2507XXXXXXXX")
        String phone,

        @Email(message = "must be a valid email address")
        @Size(max = 150, message = "must be at most 150 characters")
        String email,

        @NotBlank(message = "is required")
        @Size(max = 5, message = "must be at most 5 characters")
        String gradeCode,

        @NotNull(message = "is required")
        @Positive(message = "must be more than 0")
        @DecimalMax(value = "100000", message = "must be at most 100000 kg")
        @Digits(integer = 6, fraction = 2, message = "must have at most 2 decimals")
        BigDecimal quantityKg,

        @Size(max = 1000, message = "must be at most 1000 characters")
        String note,

        @Size(max = 200, message = "must be at most 200 characters")
        String website) {

    // Records print their fields; a buyer's name, phone and email must never end up in a log line.
    @Override
    public String toString() {
        return "PublicRequestForm[gradeCode=" + gradeCode + ", quantityKg=" + quantityKg + ", personal data hidden]";
    }
}
