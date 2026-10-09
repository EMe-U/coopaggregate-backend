package com.coopaggregate.member;

import java.time.LocalDate;

import com.coopaggregate.common.RwandanPhoneNumber;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MemberRequest(
        @NotBlank(message = "is required")
        @Size(max = 150, message = "must be at most 150 characters")
        String fullName,

        @NotBlank(message = "is required")
        @Pattern(regexp = RwandanPhoneNumber.PATTERN,
                message = "must be a Rwandan mobile number: 07XXXXXXXX, 2507XXXXXXXX or +2507XXXXXXXX")
        String phone,

        @Pattern(regexp = "^(\\d{16})?$", message = "must be 16 digits")
        String nationalId,

        @Size(max = 255, message = "must be at most 255 characters")
        String address,

        @PastOrPresent(message = "cannot be in the future")
        LocalDate joinDate,

        @Pattern(regexp = "^(rw|en)$", message = "must be rw or en")
        String preferredLanguage) {
}
