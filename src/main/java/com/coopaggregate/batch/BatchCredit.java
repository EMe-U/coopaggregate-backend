package com.coopaggregate.batch;

import java.math.BigDecimal;

/** The kg of one batch that were credited to its member, and the money for them. */
public record BatchCredit(Long deliveryId, BigDecimal creditedKg, Long grossRwf, Long deductionRwf) {

    public long earnedRwf() {
        return grossRwf - deductionRwf;
    }
}
