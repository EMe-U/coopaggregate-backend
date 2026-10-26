package com.coopaggregate.publicapi;

import com.coopaggregate.buyer.BuyerRequestStatus;

/** The buyer keeps the code; with their phone number it looks up the request later. */
public record PublicRequestCreatedResponse(String code, BuyerRequestStatus status) {
}
