package com.coopaggregate.member;

/** Result of checking a PIN a member entered. */
public enum PinCheck {
    CORRECT,
    WRONG,
    LOCKED,
    NO_PIN,
    NOT_GIVEN
}
