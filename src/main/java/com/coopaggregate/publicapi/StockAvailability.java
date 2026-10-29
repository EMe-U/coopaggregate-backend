package com.coopaggregate.publicapi;

import java.math.BigDecimal;

/** A rough stock hint for buyers. They never see the kilograms behind it. */
public enum StockAvailability {
    AVAILABLE,
    LIMITED,
    NONE;

    /** From this many sellable kilograms of a grade, it shows as AVAILABLE. Below it, LIMITED (or NONE). */
    public static final BigDecimal AVAILABLE_FROM_KG = new BigDecimal("500");

    /**
     * NONE below the smallest order a buyer may place, LIMITED from there up to AVAILABLE_FROM_KG, AVAILABLE from
     * then on. sellableKg is the grade's sellable stock as on the stock page: confirmed batches with kg left.
     */
    public static StockAvailability of(BigDecimal sellableKg, BigDecimal minOrderKg) {
        if (sellableKg.compareTo(minOrderKg) < 0) {
            return NONE;
        }
        return sellableKg.compareTo(AVAILABLE_FROM_KG) < 0 ? LIMITED : AVAILABLE;
    }
}
