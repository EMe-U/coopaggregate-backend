package com.coopaggregate.payment;

/** UNPAID: nothing paid yet. PART_PAID: something paid, something still owed. PAID: nothing owed. */
public enum BalanceStatus {
    UNPAID,
    PART_PAID,
    PAID
}
