package com.coopaggregate.batch;

import java.math.BigDecimal;

/** kg received, sold, lost and remaining, added up over batches that are not reversed. */
public record BatchTotals(BigDecimal receivedKg, BigDecimal soldKg, BigDecimal lostKg, BigDecimal remainingKg) {
}
