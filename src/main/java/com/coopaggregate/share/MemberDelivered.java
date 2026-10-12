package com.coopaggregate.share;

import java.math.BigDecimal;

/** What one member delivered to one lot: kilograms and the deductions recorded on those deliveries. */
public record MemberDelivered(Long memberId, String memberName, String memberNumber, BigDecimal kg,
                              Long deductionRwf) {
}
