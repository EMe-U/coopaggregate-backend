package com.coopaggregate.audit;

public enum AuditAction {
    // From the old member PIN, kept so earlier history rows can still be read.
    PIN_SET,
    PIN_RESET,
    SETTINGS_UPDATED,
    GRADE_UPDATED,
    PASSWORD_CHANGED,
    CONFIRMATION_CODE_RESENT,
    DELIVERY_REVIEWED
}
