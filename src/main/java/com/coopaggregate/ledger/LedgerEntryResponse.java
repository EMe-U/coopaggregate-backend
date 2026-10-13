package com.coopaggregate.ledger;

import java.math.BigDecimal;
import java.time.Instant;

/** One ledger line for the Ledger screen. Names and codes come from the record the entry points to. */
public record LedgerEntryResponse(
        Long id,
        Instant entryDate,
        LedgerEntryType type,
        String description,
        String referenceCode,
        Long memberId,
        String memberName,
        Long lotId,
        String lotCode,
        BigDecimal quantityKg,
        Long amountRwf,
        String shortHash,
        String hash,
        String previousHash,
        Long reversesEntryId,
        Long reversedByEntryId) {
}
