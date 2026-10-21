package com.coopaggregate.delivery;

public enum ConfirmationMethod {
    // The old permanent member PIN, kept only for deliveries confirmed with it before it was removed.
    PIN("Confirmed by member with PIN"),
    SMS("Confirmed by member by SMS"),
    MANAGER_REVIEW("Checked by the manager after a dispute"),
    // Deliveries from before confirmations existed. The member never confirmed them.
    RECORDED_BEFORE_CONFIRMATION("Recorded before confirmation existed");

    private final String label;

    ConfirmationMethod(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
