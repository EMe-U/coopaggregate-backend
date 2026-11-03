package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A lot as in LotResponse, with each member's kg in it. */
public record LotDetailResponse(
        Long id,
        String code,
        String gradeName,
        LotStatus status,
        boolean olderLot,
        BigDecimal capacityKg,
        BigDecimal spaceLeftKg,
        BigDecimal shortfallKg,
        BigDecimal deliveredKg,
        BigDecimal lostKg,
        BigDecimal soldKg,
        BigDecimal remainingKg,
        BigDecimal pendingConfirmationKg,
        Long saleAmountRwf,
        Long memberCount,
        LocalDate createdAt,
        LocalDate closedAt,
        List<LotMemberResponse> members) {

    public static LotDetailResponse from(LotResponse lot, List<LotMemberResponse> members) {
        return new LotDetailResponse(lot.id(), lot.code(), lot.gradeName(), lot.status(), lot.olderLot(),
                lot.capacityKg(), lot.spaceLeftKg(), lot.shortfallKg(), lot.deliveredKg(),
                lot.lostKg(), lot.soldKg(), lot.remainingKg(), lot.pendingConfirmationKg(), lot.saleAmountRwf(),
                lot.memberCount(),
                lot.createdAt(), lot.closedAt(), members);
    }
}
