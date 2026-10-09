package com.coopaggregate.sale;

/** created is false when the sale had already been recorded with the same client UUID. */
public record RecordedSale(SaleResponse sale, boolean created) {
}
