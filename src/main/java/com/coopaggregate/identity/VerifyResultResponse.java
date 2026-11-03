package com.coopaggregate.identity;

import java.time.Instant;

/**
 * A completed verification. fullName and phone (+2507XXXXXXXX) are the verified details, to show read-only in the new
 * member form; the member is registered with them, whatever the form sends. Register the member with verificationId
 * before useBefore. The person's token is never returned. A person who is already a member gets 409 instead.
 */
public record VerifyResultResponse(Long verificationId, boolean verified, VerificationMethod method, String fullName,
                                   String phone, Instant useBefore) {

    // Records print their fields; the name and phone must never end up in a log line.
    @Override
    public String toString() {
        return "VerifyResultResponse[verificationId=" + verificationId + ", ****]";
    }
}
