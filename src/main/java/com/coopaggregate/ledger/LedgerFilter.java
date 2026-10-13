package com.coopaggregate.ledger;

import java.time.LocalDate;

/** Optional filters for the ledger list. from and to are days in Kigali time, both inclusive. */
public record LedgerFilter(
        LedgerEntryType type,
        Long memberId,
        Long lotId,
        LocalDate from,
        LocalDate to,
        String search) {
}
