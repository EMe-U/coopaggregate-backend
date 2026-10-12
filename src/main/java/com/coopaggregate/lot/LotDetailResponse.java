package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record LotDetailResponse(
        Long id,
        String code,
        String gradeName,
        LotStatus status,
        BigDecimal deliveredKg,
        BigDecimal lostKg,
        BigDecimal soldKg,
        BigDecimal remainingKg,
        Long saleAmountRwf,
        Long memberCount,
        LocalDate createdAt,
        LocalDate closedAt,
        List<LotMemberResponse> members) {

    public static LotDetailResponse from(LotResponse lot, List<LotMemberResponse> members) {
        return new LotDetailResponse(lot.id(), lot.code(), lot.gradeName(), lot.status(), lot.deliveredKg(),
                lot.lostKg(), lot.soldKg(), lot.remainingKg(), lot.saleAmountRwf(), lot.memberCount(),
                lot.createdAt(), lot.closedAt(), members);
    }
}
