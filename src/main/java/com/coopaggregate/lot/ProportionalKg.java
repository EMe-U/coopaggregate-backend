package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Splits kg across the portions of a lot in proportion to what each has left, in hundredths of a kg. Each portion first
 * gets its share rounded down; the hundredths still missing go one each to the portions with the largest remainders,
 * and on a tie to the one listed first (the caller lists the older delivery first). The parts add up to exactly the kg
 * asked for, and no portion gets more than it has.
 */
public final class ProportionalKg {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private ProportionalKg() {
    }

    /** weightsKg are what each portion has left; kg must be more than 0 and at most their sum. */
    public static List<BigDecimal> split(List<BigDecimal> weightsKg, BigDecimal kg) {
        List<BigInteger> weights = weightsKg.stream().map(ProportionalKg::hundredths).toList();
        BigInteger total = weights.stream().reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger wanted = hundredths(kg);
        if (wanted.signum() <= 0 || wanted.compareTo(total) > 0) {
            throw new IllegalArgumentException("Cannot split " + kg.toPlainString() + " kg over "
                    + toKg(total).toPlainString() + " kg.");
        }

        List<BigInteger> parts = new ArrayList<>();
        List<BigInteger> remainders = new ArrayList<>();
        BigInteger given = BigInteger.ZERO;
        for (BigInteger weight : weights) {
            BigInteger[] division = wanted.multiply(weight).divideAndRemainder(total);
            parts.add(division[0]);
            remainders.add(division[1]);
            given = given.add(division[0]);
        }
        // Fewer hundredths are missing than there are portions, so each gets at most one more.
        int missing = wanted.subtract(given).intValueExact();
        List<Integer> order = IntStream.range(0, weights.size()).boxed()
                .sorted(Comparator.comparing((Integer i) -> remainders.get(i)).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        for (int k = 0; k < missing; k++) {
            int i = order.get(k);
            parts.set(i, parts.get(i).add(BigInteger.ONE));
        }
        return parts.stream().map(ProportionalKg::toKg).toList();
    }

    private static BigInteger hundredths(BigDecimal kg) {
        return kg.multiply(HUNDRED).setScale(0, java.math.RoundingMode.UNNECESSARY).toBigIntegerExact();
    }

    private static BigDecimal toKg(BigInteger hundredths) {
        return new BigDecimal(hundredths, 2);
    }
}
