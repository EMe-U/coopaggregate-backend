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
    // The registration did not match the person who verified. The details name the fields, never their values.
    // From the old comparison of typed details, kept only so earlier history rows can still be read.
    IDENTITY_MISMATCH,
    IDENTITY_VERIFICATION_FAILED,
    // Changes the manager makes to buyers' purchase requests. The details hold the request code, not personal data.
    BUYER_REQUEST_STATUS_CHANGED,
    BUYER_REQUEST_CONVERTED,
    // A sale passed over the oldest batches. The details hold the sale code, the receipt codes and the reason.
    SALE_FIFO_SKIPPED,
    // The lot size setting changed. The details hold the old and new size and the reason.
    LOT_SIZE_CHANGED,
    // TEMPORARY, for the demo only: a delivery confirmed with SMS_DEMO_CODE. The details never hold the code.
    DEMO_CODE_CONFIRMATION
}
