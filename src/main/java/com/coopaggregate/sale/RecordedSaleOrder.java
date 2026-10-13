package com.coopaggregate.sale;

/** created is false when the order had already been recorded with the same client UUID. */
public record RecordedSaleOrder(SaleOrderResponse order, boolean created) {
}
