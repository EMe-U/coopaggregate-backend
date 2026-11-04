package com.coopaggregate.identity;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A completed verification. fullName and phone (+2507XXXXXXXX) are always verified; birthdate (yyyy-MM-dd), gender,
 * email and address (one line) are null when eSignet did not release them. verifiedFields names the fields that
 * hold a verified value, out of fullName, phone, birthdate, gender, email and address. Show them read-only in the new
 * member form: the member is registered with them, and a form that sends one of them is refused. Register the member
 * with verificationId before useBefore. The person's token is never returned. A person who is already a member gets
 * 409 instead.
 */
public record VerifyResultResponse(Long verificationId, boolean verified, VerificationMethod method, String fullName,
                                   String phone, Instant useBefore, LocalDate birthdate, String gender, String email,
                                   String address, List<String> verifiedFields) {

    static VerifyResultResponse of(Long verificationId, VerificationMethod method, VerifiedDetails details,
                                   Instant useBefore) {
        return new VerifyResultResponse(verificationId, true, method, details.fullName(), details.phone(), useBefore,
                details.birthdate(), details.gender(), details.email(), details.address(), details.verifiedFields());
    }

    // Records print their fields; the details must never end up in a log line.
    @Override
    public String toString() {
        return "VerifyResultResponse[verificationId=" + verificationId + ", ****]";
    }
}
