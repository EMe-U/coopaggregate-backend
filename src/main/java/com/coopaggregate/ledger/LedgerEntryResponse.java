package com.coopaggregate.ledger;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One ledger line for the Ledger screen. Names and codes come from the record the entry points to.
 * reversedType is the type of the cancelled entry, only for REVERSAL entries.
 */
public record LedgerEntryResponse(
        Long id,
        Instant entryDate,
        LedgerEntryType type,
        LedgerEntryType reversedType,
        String description,
        String referenceCode,
        Long memberId,
        String memberName,
        String buyerName,
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
