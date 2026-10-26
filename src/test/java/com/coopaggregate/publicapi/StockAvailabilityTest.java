package com.coopaggregate.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class StockAvailabilityTest {

    @ParameterizedTest(name = "{0} kg left with a minimum order of {1} kg is {2}")
    @CsvSource({
            "0, 100, NONE",
            "99.99, 100, NONE",
            "100, 100, LIMITED",
            "100.00, 100, LIMITED",
            "499.99, 100, LIMITED",
            "500, 100, AVAILABLE",
            "500.00, 100, AVAILABLE",
            "12000, 100, AVAILABLE",
            "550, 600, NONE",
            "600, 600, AVAILABLE"})
    void levelFollowsTheMinimumOrderAnd500Kg(String remainingKg, String minOrderKg, StockAvailability expected) {
        assertEquals(expected, StockAvailability.of(new BigDecimal(remainingKg), new BigDecimal(minOrderKg)));
    }

    @Test
    void availableStartsAtTheNamedConstantOf500Kg() {
        assertEquals(new BigDecimal("500"), StockAvailability.AVAILABLE_FROM_KG);
    }
}
