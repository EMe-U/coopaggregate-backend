package com.coopaggregate.share;

import java.math.BigDecimal;
import java.util.List;

public record SharePreviewResponse(
        String lotCode,
        String gradeName,
        BigDecimal deliveredKg,
        long saleAmountRwf,
        List<MemberShare> members,
        Totals totals) {

    public record MemberShare(
            Long memberId,
            String memberName,
            String memberNumber,
            BigDecimal memberKg,
            BigDecimal sharePercent,
            long shareRwf,
            long deductionRwf,
            long netRwf) {
    }

    public record Totals(long shareRwf, long deductionRwf, long netRwf) {
    }
}
