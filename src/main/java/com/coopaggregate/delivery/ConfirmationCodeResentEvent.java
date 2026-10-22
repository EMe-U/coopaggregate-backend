package com.coopaggregate.delivery;

/** Published when the manager asks for a new code, so it is sent once the request is committed. */
public record ConfirmationCodeResentEvent(Long deliveryId, int sendNumber) {
}
