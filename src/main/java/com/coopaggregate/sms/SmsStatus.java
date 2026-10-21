package com.coopaggregate.sms;

public enum SmsStatus {
    QUEUED,
    SENT,
    FAILED,
    // A code message that was no longer needed before it could be sent.
    CANCELLED
}
