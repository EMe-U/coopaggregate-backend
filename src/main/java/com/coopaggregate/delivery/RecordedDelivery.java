package com.coopaggregate.delivery;

/** created is false when the delivery had already been recorded with the same client UUID. */
public record RecordedDelivery(DeliveryResponse delivery, boolean created) {
}
