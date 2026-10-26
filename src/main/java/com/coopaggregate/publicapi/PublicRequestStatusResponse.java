package com.coopaggregate.publicapi;

import com.coopaggregate.buyer.BuyerRequestStatus;

/** What a buyer sees about their request: the status and the manager's public message, nothing else. */
public record PublicRequestStatusResponse(String code, BuyerRequestStatus status, String message) {
}
