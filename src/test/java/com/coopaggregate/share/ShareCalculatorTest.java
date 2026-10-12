package com.coopaggregate.share;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class ShareCalculatorTest {

    @Test
    void aliceExampleGivesExactShares() {
        // Lot of 10,000 kg sold for 2,952,000 RWF; members delivered 5,000 / 3,000 / 2,000 kg.
        List<Long> shares = ShareCalculator.split(2_952_000L, kg("5000", "3000", "2000"));

        assertEquals(List.of(1_476_000L, 885_600L, 590_400L), shares);
    }

    @Test
    void remainderGoesToLargestMemberSoSharesAddUp() {
        // 10 RWF over 1/1/1/3 kg: 1.67 + 1.67 + 1.67 + 5 rounds to 2 + 2 + 2 + 5 = 11, one franc too many.
        List<Long> shares = ShareCalculator.split(10L, kg("1", "1", "1", "3"));

        assertEquals(List.of(2L, 2L, 2L, 4L), shares);
        assertEquals(10L, sum(shares));
    }

    @Test
    void missingFrancGoesToLargestMember() {
        // 1,000 RWF over three equal members: 333.33 each rounds to 999, so the first member gets the extra franc.
        List<Long> shares = ShareCalculator.split(1_000L, kg("40.00", "40.00", "40.00"));

        assertEquals(List.of(334L, 333L, 333L), shares);
    }

    @Test
    void sharesAlwaysAddUpToTheSaleAmount() {
        List<Long> shares = ShareCalculator.split(2_952_001L, kg("1234.56", "987.65", "432.10", "0.01", "77.77"));

        assertEquals(2_952_001L, sum(shares));
    }

    @Test
    void decimalKilogramsAreSharedExactly() {
        List<Long> shares = ShareCalculator.split(64_000L, kg("120.50", "79.50"));

        assertEquals(List.of(38_560L, 25_440L), shares);
    }

    @Test
    void percentHasTwoDecimals() {
        assertEquals(new BigDecimal("50.00"), ShareCalculator.percent(new BigDecimal("5000"), new BigDecimal("10000")));
        assertEquals(new BigDecimal("33.33"), ShareCalculator.percent(new BigDecimal("1"), new BigDecimal("3")));
    }

    private static List<BigDecimal> kg(String... values) {
        return Arrays.stream(values).map(BigDecimal::new).toList();
    }

    private static long sum(List<Long> shares) {
        return shares.stream().mapToLong(Long::longValue).sum();
    }
}
