package com.coopaggregate.share;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** Splits a lot's sale money between members in proportion to the kilograms they delivered. */
public final class ShareCalculator {

    private ShareCalculator() {
    }

    /**
     * Returns one share per entry in memberKgs, in whole RWF. Each share is rounded; the rounding remainder
     * goes to the member with the most kg (the first one on a tie), so the shares always add up to amountRwf.
     */
    public static List<Long> split(long amountRwf, List<BigDecimal> memberKgs) {
        BigDecimal totalKg = memberKgs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Long> shares = new ArrayList<>();
        int largest = 0;
        for (int i = 0; i < memberKgs.size(); i++) {
            // Multiplying before dividing keeps the result exact until the final rounding.
            shares.add(memberKgs.get(i).multiply(BigDecimal.valueOf(amountRwf))
                    .divide(totalKg, 0, RoundingMode.HALF_UP)
                    .longValueExact());
            if (memberKgs.get(i).compareTo(memberKgs.get(largest)) > 0) {
                largest = i;
            }
        }

        long remainder = amountRwf - shares.stream().mapToLong(Long::longValue).sum();
        if (!shares.isEmpty()) {
            shares.set(largest, shares.get(largest) + remainder);
        }
        return shares;
    }

    public static BigDecimal percent(BigDecimal memberKg, BigDecimal totalKg) {
        return memberKg.multiply(BigDecimal.valueOf(100)).divide(totalKg, 2, RoundingMode.HALF_UP);
    }
}
