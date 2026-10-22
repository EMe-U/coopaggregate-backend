package com.coopaggregate.identity;

import java.time.Instant;

/**
 * A completed verification. Pass verificationId as verificationId when registering the member, before
 * useBefore. The person's token is never returned. A person who is already a member gets 409 instead.
 */
public record VerifyResultResponse(Long verificationId, boolean verified, VerificationMethod method,
                                   Instant useBefore) {
}
