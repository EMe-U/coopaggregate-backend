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

        // May stay empty only for a member who has none (registered before it was required). When given, exactly 16
        // digits (spaces around it are trimmed) and unique; one that is set cannot be removed. Checked in
        // MemberService, so the errors carry NATIONAL_ID_REQUIRED, NATIONAL_ID_INVALID or NATIONAL_ID_TAKEN.
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
