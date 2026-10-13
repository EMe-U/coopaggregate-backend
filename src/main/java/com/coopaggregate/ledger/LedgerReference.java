package com.coopaggregate.ledger;

/** The code, member, lot and buyer of the business record a ledger entry points to. Any part can be null. */
public record LedgerReference(String referenceCode, Long memberId, String memberName, Long lotId, String lotCode,
                              String buyerName) {

    static final LedgerReference NONE = new LedgerReference(null, null, null, null, null, null);
}
