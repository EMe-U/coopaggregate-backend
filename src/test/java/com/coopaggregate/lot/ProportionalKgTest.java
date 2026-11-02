package com.coopaggregate.lot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class ProportionalKgTest {

    @Test
    void kgAreSplitInProportionToWhatEachPortionHasLeft() {
        assertEquals(kgs("25.00", "50.00", "25.00"), ProportionalKg.split(kgs("40", "80", "40"), new BigDecimal("100")));
        assertEquals(kgs("30.00", "20.00"), ProportionalKg.split(kgs("60", "40"), new BigDecimal("50")));
    }

    @Test
    void theMissingHundredthsGoToTheLargestRemaindersFirst() {
        // 10 kg over 3 equal portions: 3.33 each, and the last hundredth to the first listed (ties to the older one).
        assertEquals(kgs("3.34", "3.33", "3.33"), ProportionalKg.split(kgs("5", "5", "5"), new BigDecimal("10")));
        // 1 kg over 7, 11 and 13 kg: exact shares 0.2258, 0.3548, 0.4193 -> 0.22, 0.35, 0.41 and 2 hundredths missing,
        // which go to the two largest remainders: .93 (13 kg) and .58 (7 kg); .48 (11 kg) gets none.
        assertEquals(kgs("0.23", "0.35", "0.42"), ProportionalKg.split(kgs("7", "11", "13"), new BigDecimal("1")));
    }

    @Test
    void onATieTheOlderPortionListedFirstGetsTheHundredth() {
        assertEquals(kgs("0.01", "0.00"), ProportionalKg.split(kgs("3", "3"), new BigDecimal("0.01")));
        assertEquals(kgs("0.00", "0.01"), ProportionalKg.split(kgs("3", "3.01"), new BigDecimal("0.01")));
    }

    @Test
    void takingEverythingEmptiesEveryPortion() {
        assertEquals(kgs("13.07", "0.01", "86.92"), ProportionalKg.split(kgs("13.07", "0.01", "86.92"),
                new BigDecimal("100.00")));
    }

    @Test
    void aPortionWithNothingLeftGetsNothing() {
        assertEquals(kgs("0.00", "4.00"), ProportionalKg.split(kgs("0", "8"), new BigDecimal("4")));
    }

    @Test
    void thePartsAlwaysAddUpExactlyAndNeverExceedWhatAPortionHas() {
        Random random = new Random(7);
        for (int run = 0; run < 2000; run++) {
            List<BigDecimal> weights = new ArrayList<>();
            int portions = 1 + random.nextInt(25);
            for (int i = 0; i < portions; i++) {
                weights.add(BigDecimal.valueOf(1 + random.nextInt(10_000), 2));
            }
            long total = weights.stream().mapToLong(w -> w.movePointRight(2).longValueExact()).sum();
            BigDecimal kg = BigDecimal.valueOf(1 + (long) (random.nextDouble() * total), 2);

            List<BigDecimal> parts = ProportionalKg.split(weights, kg);

            assertEquals(0, kg.compareTo(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)), weights + " " + kg);
            for (int i = 0; i < portions; i++) {
                assertTrue(parts.get(i).signum() >= 0 && parts.get(i).compareTo(weights.get(i)) <= 0,
                        weights + " " + kg + " " + parts);
                assertEquals(2, parts.get(i).scale());
            }
        }
    }

    @Test
    void moreThanThePortionsHaveIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProportionalKg.split(kgs("1", "2"), new BigDecimal("3.01")));

        assertEquals("Cannot split 3.01 kg over 3.00 kg.", e.getMessage());
    }

    private static List<BigDecimal> kgs(String... values) {
        return List.of(values).stream().map(BigDecimal::new).toList();
    }
}
