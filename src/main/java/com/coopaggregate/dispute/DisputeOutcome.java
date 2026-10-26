package com.coopaggregate.dispute;

/** How a settled dispute ended. Read from the delivery; nothing extra is stored. */
public enum DisputeOutcome {
    // resolve-dispute: the manager checked the delivery and found it correct.
    RESOLVED,
    // The delivery was reversed.
    REVERSED,
    // The delivery was replaced by a corrected one (correctedById).
    CORRECTED
}
