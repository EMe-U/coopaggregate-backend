package com.coopaggregate.lot;

import java.math.BigDecimal;

/** Totals for one lot, summed from its deliveries, losses and sales. Sums of no rows arrive as null. */
public record LotQuantities(
        Long lotId,
        BigDecimal deliveredKg,
        BigDecimal lostKg,
        BigDecimal soldKg,
        Long saleAmountRwf,
        Long memberCount) {

    public LotQuantities {
        deliveredKg = deliveredKg == null ? BigDecimal.ZERO : deliveredKg;
        lostKg = lostKg == null ? BigDecimal.ZERO : lostKg;
        soldKg = soldKg == null ? BigDecimal.ZERO : soldKg;
        saleAmountRwf = saleAmountRwf == null ? 0L : saleAmountRwf;
        memberCount = memberCount == null ? 0L : memberCount;
    }

    public static LotQuantities empty(Long lotId) {
        return new LotQuantities(lotId, null, null, null, null, null);
    }

    public BigDecimal remainingKg() {
        return deliveredKg.subtract(lostKg).subtract(soldKg);
    }
}
