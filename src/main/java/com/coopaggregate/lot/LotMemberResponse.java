package com.coopaggregate.lot;

import java.math.BigDecimal;

public record LotMemberResponse(Long memberId, String memberName, String memberNumber, BigDecimal kg) {
}
