package com.coopaggregate.member;

import java.time.LocalDate;

import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

/**
 * Registers a member from a completed identity verification. The name and phone come from the verification, so they
 * must not be sent: a request that sends them is refused, whatever their value.
 */
public record NewMemberRequest(
        // From POST /api/members/verify/callback.
        Long verificationId,

        @Null(message = "must not be sent: the name comes from the identity verification")
        String fullName,

        @Null(message = "must not be sent: the phone number comes from the identity verification")
        String phone,

        // Optional: leave it out or empty. When given, it must be 16 digits and unique.
        @Pattern(regexp = "^(\\d{16})?$", message = "must be 16 digits")
        String nationalId,

        @Size(max = 255, message = "must be at most 255 characters")
        String address,

        @PastOrPresent(message = "cannot be in the future")
        LocalDate joinDate,

        @Pattern(regexp = "^(rw|en)$", message = "must be rw or en")
        String preferredLanguage) {

    // Records print their fields; the member's details must never end up in a log line.
    @Override
    public String toString() {
        return "NewMemberRequest[verificationId=" + verificationId + ", ****]";
    }
}
