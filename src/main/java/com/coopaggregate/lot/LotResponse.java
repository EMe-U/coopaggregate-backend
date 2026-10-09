package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.time.LocalDate;

public record LotResponse(
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
        LocalDate closedAt) {

    public static LotResponse from(Lot lot, LotQuantities quantities) {
        return new LotResponse(
                lot.getId(),
                lot.getLotCode(),
                lot.getGrade().getName(),
                lot.getStatus(),
                quantities.deliveredKg(),
                quantities.lostKg(),
                quantities.soldKg(),
                quantities.remainingKg(),
                quantities.saleAmountRwf(),
                quantities.memberCount(),
                lot.getOpenDate(),
                lot.getCloseDate());
    }
}
