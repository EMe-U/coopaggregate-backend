package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class IdentityAvailabilityTest {

    private static IdentityAvailability availability(boolean enabled) {
        return availability(enabled, false);
    }

    private static IdentityAvailability availability(boolean enabled, boolean registerUnverified) {
        return new IdentityAvailability(new MosipProperties(enabled, "http://localhost:8080", "coopaggregate", "r", "k",
                "kid", null, null, null, null, null, null, null, null, null, null, null, null, false),
                registerUnverified);
    }

    @Test
    void theFeatureIsOffByDefaultAndSoNotRequired() {
        assertEquals(new IdentityStatusResponse(false, false, false), availability(false).status());
    }

    @Test
    void switchedOnMeansAvailableAndRequired() {
        assertTrue(availability(true).isAvailable());
        assertTrue(availability(true).isRequired());
        assertEquals(new IdentityStatusResponse(true, true, false), availability(true).status());
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

    @Test
    void memberRegisterUnverifiedIsHonouredOnlyWhileTheProviderIsOff(CapturedOutput output) {
        IdentityAvailability availability = availability(false, true);

        assertTrue(availability.isUnverifiedAllowed());
        assertEquals(new IdentityStatusResponse(false, false, true), availability.status());
        assertTrue(output.getOut().contains("members are registered WITHOUT identity verification"), output.getOut());
    }

    @Test
    void memberRegisterUnverifiedIsIgnoredWhileTheProviderIsOn(CapturedOutput output) {
        IdentityAvailability availability = availability(true, true);

        assertFalse(availability.isUnverifiedAllowed());
        assertEquals(new IdentityStatusResponse(true, true, false), availability.status());
        assertTrue(output.getOut().contains("MEMBER_REGISTER_UNVERIFIED=true is ignored because MOSIP_ENABLED=true"),
                output.getOut());
    }
}
