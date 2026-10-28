package com.coopaggregate.ledger;

public enum LedgerEntryType {
    DELIVERY,
    LOSS,
    SALE,
    SHARE,
    PAYMENT,
    REVERSAL,
    // What a sale pays one member for one batch it used (a sale_allocation row).
    SALE_CREDIT
}
