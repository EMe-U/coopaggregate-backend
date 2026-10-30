package com.coopaggregate.publicapi;

import com.coopaggregate.buyer.BuyerContactMethod;
import com.coopaggregate.buyer.BuyerRequestStatus;

/**
 * What a buyer sees about their request: the status, the manager's public message, and how the cooperative will
 * contact them (CALL or WHATSAPP) on which number. The number is one the buyer gave; nothing else is shown.
 */
public record PublicRequestStatusResponse(String code, BuyerRequestStatus status, String message,
                                          BuyerContactMethod contactMethod, String contactNumber) {
}
