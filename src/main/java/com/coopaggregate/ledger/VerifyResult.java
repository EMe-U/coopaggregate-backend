package com.coopaggregate.ledger;

public record VerifyResult(boolean valid, long checkedEntries, Long firstBrokenEntryId) {
}
