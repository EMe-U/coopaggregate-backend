package com.coopaggregate.delivery;

/**
 * Published when a delivery becomes CONFIRMED (by code, by SMS reply or by a manager review), so the member can be
 * sent a receipt once the confirmation is committed.
 */
public record DeliveryConfirmedEvent(Long deliveryId) {
}
