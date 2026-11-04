package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * deliveredKg is the kg in the lot (its portions of deliveries that are not reversed). A fixed-size lot has its
 * capacityKg: while FILLING, spaceLeftKg is what it can still take; once FULL or SOLD, shortfallKg is what reversals
 * took out of it since (a full lot is never filled again). olderLot is true for the lots from before fixed-size lots,
 * which have no capacity (OPEN, CLOSED, SOLD or SHARED); both are 0 for them.
 */
public record LotResponse(
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
        LocalDate closedAt) {

    public static LotResponse from(Lot lot, LotQuantities quantities) {
        BigDecimal capacity = lot.getCapacityKg();
        BigDecimal missing = capacity == null ? BigDecimal.ZERO
                : capacity.subtract(quantities.deliveredKg()).max(BigDecimal.ZERO);
        boolean filling = lot.getStatus() == LotStatus.FILLING;
        return new LotResponse(
                lot.getId(),
                lot.getLotCode(),
                lot.getGrade().getName(),
                lot.getStatus(),
                capacity == null,
                capacity,
                filling ? missing : BigDecimal.ZERO,
                filling ? BigDecimal.ZERO : missing,
                quantities.deliveredKg(),
                quantities.lostKg(),
                quantities.soldKg(),
                quantities.remainingKg(),
                quantities.pendingConfirmationKg(),
                quantities.saleAmountRwf(),
                quantities.memberCount(),
                lot.getOpenDate(),
                lot.getCloseDate());
    }
}
