package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IdentityAvailabilityTest {

    private static IdentityAvailability availability(boolean enabled) {
        return new IdentityAvailability(new MosipProperties(enabled, "http://localhost:8080", "coopaggregate", "r", "k",
                "kid", null, null, null, null, null, null, null, null, null));
    }

    @Test
    void theFeatureIsOffByDefaultAndSoNotRequired() {
        assertEquals(new IdentityStatusResponse(false, false), availability(false).status());
    }

    @Test
    void switchedOnMeansAvailableAndRequired() {
        assertTrue(availability(true).isAvailable());
        assertTrue(availability(true).isRequired());
        assertEquals(new IdentityStatusResponse(true, true), availability(true).status());
    }

    @Test
    void theSettingsHaveNoSwitchForTheRequirement() {
        for (Class<?> type : new Class<?>[] {com.coopaggregate.setting.SettingsRequest.class,
                com.coopaggregate.setting.SettingsResponse.class}) {
            for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
                assertFalse(component.getName().toLowerCase().contains("identity"), component.getName());
            }
        }
    }

    @Test
    void requiredIsNeverTrueWhileTheProviderIsOff() {
        assertFalse(availability(false).isRequired());
    }
}
