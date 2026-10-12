package com.coopaggregate.lot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class LotQuantitiesTest {

    @Test
    void remainingIsDeliveredMinusLostMinusSold() {
        LotQuantities quantities = new LotQuantities(1L, new BigDecimal("1000.00"), new BigDecimal("25.50"),
                new BigDecimal("400.00"), 120000L, 7L);

        assertEquals(new BigDecimal("574.50"), quantities.remainingKg());
    }

    @Test
    void missingSumsCountAsZero() {
        LotQuantities quantities = new LotQuantities(1L, new BigDecimal("300.00"), null, null, null, null);

        assertEquals(new BigDecimal("300.00"), quantities.remainingKg());
        assertEquals(BigDecimal.ZERO, quantities.lostKg());
        assertEquals(0L, quantities.saleAmountRwf());
        assertEquals(0L, quantities.memberCount());
    }

    @Test
    void fullySoldLotHasNothingRemaining() {
        LotQuantities quantities = new LotQuantities(1L, new BigDecimal("500.00"), new BigDecimal("20.00"),
                new BigDecimal("480.00"), 144000L, 3L);

        assertEquals(0, quantities.remainingKg().compareTo(BigDecimal.ZERO));
    }
}
