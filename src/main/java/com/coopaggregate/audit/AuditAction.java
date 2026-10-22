package com.coopaggregate.audit;

public enum AuditAction {
    // From the old member PIN, kept so earlier history rows can still be read.
    PIN_SET,
    PIN_RESET,
    SETTINGS_UPDATED,
    GRADE_UPDATED,
    PASSWORD_CHANGED,
    CONFIRMATION_CODE_RESENT,
    DELIVERY_REVIEWED,
    // Results of identity verification at registration. The details never hold personal data or tokens.
    IDENTITY_VERIFIED,
    IDENTITY_DUPLICATE,
    IDENTITY_VERIFICATION_FAILED
}
