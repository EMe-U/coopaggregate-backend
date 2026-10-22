package com.coopaggregate.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class ConfirmationMethodTest {

    @Test
    void oldDeliveriesAreNeverShownAsConfirmedByTheMember() {
        String label = ConfirmationMethod.RECORDED_BEFORE_CONFIRMATION.label();

        assertEquals("Recorded before confirmation existed", label);
        assertFalse(label.toLowerCase().contains("confirmed by member"));
    }
}
