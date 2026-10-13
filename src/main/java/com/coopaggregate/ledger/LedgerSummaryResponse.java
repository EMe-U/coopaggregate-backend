package com.coopaggregate.ledger;

import java.time.Instant;

/** lastEntryAt is null while the ledger is empty. */
public record LedgerSummaryResponse(long totalEntries, Instant lastEntryAt, long entriesToday) {
}
