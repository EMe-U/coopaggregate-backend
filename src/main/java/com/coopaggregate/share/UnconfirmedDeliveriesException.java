package com.coopaggregate.share;

import java.util.List;

/** A lot cannot be shared while some of its deliveries are pending or disputed. Answered with 409. */
public class UnconfirmedDeliveriesException extends IllegalStateException {

    private final List<UnconfirmedDelivery> deliveries;

    public UnconfirmedDeliveriesException(String message, List<UnconfirmedDelivery> deliveries) {
        super(message);
        this.deliveries = deliveries;
    }

    public List<UnconfirmedDelivery> getDeliveries() {
        return deliveries;
    }
}
