package com.coopaggregate.member;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonAlias;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

/**
 * Registers a member from a completed identity verification. The name, phone, birth date, gender, email and address
 * the check verified are taken from the verification: a request that sends a value for one of them is refused with
 * VERIFIED_FIELD_LOCKED. The name and phone are always verified, so they are never sent. An address that was not
 * verified may be typed as before; a birth date, gender or email can only come from the check. Only with
 * MOSIP_ENABLED=false and MEMBER_REGISTER_UNVERIFIED=true (demos and fallbacks) are the name and phone sent instead of
 * verificationId, and the member is stored as not verified.
 */
public record NewMemberRequest(
        // From POST /api/members/verify/callback.
        Long verificationId,

        // Never sent: always verified.
        String fullName,

        // Never sent: always verified.
        String phone,

        // Optional: leave it out or empty. When given, it must be 16 digits and unique.
        @Pattern(regexp = "^(\\d{16})?$", message = "must be 16 digits")
        String nationalId,

        // Only when the check did not verify one.
        @Size(max = 255, message = "must be at most 255 characters")
        String address,

        @PastOrPresent(message = "cannot be in the future")
        LocalDate joinDate,

        @Pattern(regexp = "^(rw|en)$", message = "must be rw or en")
        String preferredLanguage,

        // Never sent: these only come from the check. Here so a form that sends them is refused, not silently ignored.
        @JsonAlias("birthdate")
        LocalDate birthDate,

        String gender,

        String email) {

    /** A registration that sends none of the verified-only details. */
    public NewMemberRequest(Long verificationId, String fullName, String phone, String nationalId, String address,
                            LocalDate joinDate, String preferredLanguage) {
        this(verificationId, fullName, phone, nationalId, address, joinDate, preferredLanguage, null, null, null);
    }

    // Records print their fields; the member's details must never end up in a log line.
    @Override
    public String toString() {
        return "NewMemberRequest[verificationId=" + verificationId + ", ****]";
    }
}
