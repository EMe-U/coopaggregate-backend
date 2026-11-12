package com.coopaggregate.sms;

public enum SmsMessageType {
    // A new delivery with its one-time code; send numbers 1 to 3 are new codes the manager sent.
    DELIVERY_CODE,
    // One per delivery, once it is confirmed.
    RECEIPT,
    // One per payment to a member, and one more if that payment is reversed.
    PAYMENT,
    PAYMENT_REVERSED
}
