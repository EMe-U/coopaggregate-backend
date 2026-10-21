package com.coopaggregate.share;

import java.util.List;

/** The 409 body when a lot still has unconfirmed deliveries: the usual message plus the deliveries to fix. */
public record UnconfirmedDeliveriesError(String message, List<UnconfirmedDelivery> deliveries) {
}
