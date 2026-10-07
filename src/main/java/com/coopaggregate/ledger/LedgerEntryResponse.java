package com.coopaggregate.ledger;

import java.math.BigDecimal;
import java.time.Instant;

public record LedgerEntryResponse(
        Long id,
        Instant entryDate,
        LedgerEntryType entryType,
        String relatedEntityType,
        Long relatedEntityId,
        BigDecimal quantityKg,
        Long amount,
        String reason,
        Long reversesId,
        Long reversedBy,
        String shortHash) {

    public static LedgerEntryResponse from(LedgerEntry entry, Long reversedBy) {
        return new LedgerEntryResponse(
                entry.getId(),
                entry.getEntryDate(),
                entry.getEntryType(),
                entry.getRelatedEntityType(),
                entry.getRelatedEntityId(),
                entry.getQuantityKg(),
                entry.getAmount(),
                entry.getReason(),
                entry.getReverses() == null ? null : entry.getReverses().getId(),
                reversedBy,
                entry.getCurrentHash().substring(0, 8));
    }
}
