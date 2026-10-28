package com.coopaggregate.batch;

/** How the kg sold from a batch are paid to its member. */
public enum BatchPayment {
    /** Nothing sold yet. */
    NOTHING_SOLD,
    /** Every kg sold was credited to the member by batch, at the time of the sale. */
    BATCH,
    /** Sold before sales by grade, and paid through the lot's share. */
    LOT_SHARE,
    /** Some kg were sold before sales by grade and their lot is not shared yet, so they are not paid yet. */
    AWAITING_SETTLEMENT
}
