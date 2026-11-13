package com.coopaggregate.payment;

/** OWED: the member has a balance to disburse. SETTLED: nothing is owed (balance 0). */
public enum SettlementStatus {
    OWED,
    SETTLED;

    public static SettlementStatus of(long balanceRwf) {
        return balanceRwf > 0 ? OWED : SETTLED;
    }
}
