package com.coopaggregate.buyer;

import java.time.Instant;

public record BuyerResponse(Long id, String name, String phone, String type, Instant createdAt) {

    public static BuyerResponse from(Buyer buyer) {
        return new BuyerResponse(buyer.getId(), buyer.getName(), buyer.getPhone(), buyer.getType(),
                buyer.getCreatedAt());
    }
}
