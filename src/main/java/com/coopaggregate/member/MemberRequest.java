package com.coopaggregate.member;

import java.time.LocalDate;

import com.coopaggregate.common.RwandanPhoneNumber;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Updates a member's details. A new member is registered with NewMemberRequest instead. */
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

        // 500, like a verified address, so a member with a long verified address can still be edited.
        @Size(max = 500, message = "must be at most 500 characters")
        String address,

        @PastOrPresent(message = "cannot be in the future")
        LocalDate joinDate,

        @Pattern(regexp = "^(rw|en)$", message = "must be rw or en")
        String preferredLanguage) {

    // Records print their fields; the member's details must never end up in a log line.
    @Override
    public String toString() {
        return "MemberRequest[****]";
    }
}
