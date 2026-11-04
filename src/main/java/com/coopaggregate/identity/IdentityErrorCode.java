package com.coopaggregate.identity;

/** The code of an identity verification error, sent with the message so the frontend can show its own text. */
public enum IdentityErrorCode {
    // 400: the state or verification is unknown, or another manager's.
    VERIFICATION_UNKNOWN,
    // 400: the state is more than 10 minutes old, or the verification more than 30 minutes.
    VERIFICATION_EXPIRED,
    // 409: the state, or the verification, was already used.
    VERIFICATION_USED,
    // 409: this person is already a member.
    ALREADY_MEMBER,
    // 400: eSignet did not share the name or the phone.
    CLAIMS_MISSING,
    // 400: the verified phone is not a Rwandan mobile number.
    PHONE_NOT_RWANDAN,
    // 400: the verified name is longer than 150 characters.
    NAME_TOO_LONG,
    // 400: the person logged in at eSignet in a way that is not in MOSIP_ALLOWED_ACRS (for example biometrics).
    ACR_NOT_ALLOWED,
    // 502: eSignet failed or its answer could not be trusted.
    PROVIDER_FAILED,
    // 503: identity verification is switched off.
    NOT_CONFIGURED
}
