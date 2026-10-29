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
    // From identity verification, which was removed until the real service is ready. Kept so old history
    // entries still load (like PIN_SET and PIN_RESET).
    IDENTITY_VERIFIED,
    IDENTITY_DUPLICATE,
    IDENTITY_VERIFICATION_FAILED,
    // Changes the manager makes to buyers' purchase requests. The details hold the request code, not personal data.
    BUYER_REQUEST_STATUS_CHANGED,
    BUYER_REQUEST_CONVERTED,
    // A sale passed over the oldest batches. The details hold the sale code, the receipt codes and the reason.
    SALE_FIFO_SKIPPED
}
