package com.coopaggregate.report;

import com.coopaggregate.delivery.ConfirmationMethod;

/** How many deliveries were confirmed one way. */
public record MethodCount(ConfirmationMethod method, Long deliveries) {
}
