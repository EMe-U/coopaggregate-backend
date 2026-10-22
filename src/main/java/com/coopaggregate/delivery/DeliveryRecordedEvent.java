package com.coopaggregate.delivery;

/** Published when a new delivery is saved, so the member can be sent an SMS once the save is committed. */
public record DeliveryRecordedEvent(Long deliveryId) {
}
