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

        // Optional: leave it out or empty. When given, it must be 16 digits and unique.
        @Pattern(regexp = "^(\\d{16})?$", message = "must be 16 digits")
        String nationalId,

        @Size(max = 255, message = "must be at most 255 characters")
        String address,

        @PastOrPresent(message = "cannot be in the future")
        LocalDate joinDate,

        @Pattern(regexp = "^(rw|en)$", message = "must be rw or en")
        String preferredLanguage,

        // From POST /api/members/verify/callback. Only used when registering; ignored when updating.
        Long verificationId,

        // Only for the identity check: required with verificationId, compared with the person who verified, and never
        // saved. Ignored without a verificationId and when updating.
        @PastOrPresent(message = "cannot be in the future")
        LocalDate birthDate) {

    // Records print their fields; the birth date and the verification must never end up in a log line.
    @Override
    public String toString() {
        return "MemberRequest[****]";
    }
}
