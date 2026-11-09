package com.coopaggregate.member;

/** Why a national ID was refused. Sent as "code" next to the message. */
public enum NationalIdErrorCode {
    // 400: a new member needs one, and an ID that is set can never be removed.
    NATIONAL_ID_REQUIRED,
    // 400: not exactly 16 digits.
    NATIONAL_ID_INVALID,
    // 409: another member already has it. The answer names that member's code only.
    NATIONAL_ID_TAKEN
}
