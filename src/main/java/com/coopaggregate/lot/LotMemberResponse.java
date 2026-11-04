package com.coopaggregate.lot;

import java.math.BigDecimal;

/** A member's kg in a lot (kg), and how much of it was sold, lost and is left. */
public record LotMemberResponse(Long memberId, String memberName, String memberNumber, BigDecimal kg,
                                BigDecimal soldKg, BigDecimal lostKg, BigDecimal remainingKg) {
}
