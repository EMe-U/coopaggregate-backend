package com.coopaggregate.identity;

import java.time.Instant;

/**
 * A completed verification. Pass verificationId as verificationId (with the member's birthDate) when registering
 * the member, before useBefore. Neither the person's token nor their details are returned. A person who is already a
 * member gets 409 instead.
 */
public record VerifyResultResponse(Long verificationId, boolean verified, VerificationMethod method,
                                   Instant useBefore) {
}
