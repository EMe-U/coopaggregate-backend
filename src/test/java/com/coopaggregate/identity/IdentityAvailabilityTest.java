package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

class IdentityAvailabilityTest {

    private final SettingRepository settingRepository = mock(SettingRepository.class);
    private final Setting setting = new Setting();

    IdentityAvailabilityTest() {
        when(settingRepository.findById(1L)).thenReturn(Optional.of(setting));
    }

    private IdentityAvailability availability(boolean enabled) {
        return new IdentityAvailability(new MosipProperties(enabled, "http://localhost:8080", "coopaggregate", "r", "k",
                "kid", null, null, null, null, null, null, null), settingRepository);
    }

    @Test
    void theFeatureIsOffAndNotRequiredByDefault() {
        assertEquals(new IdentityStatusResponse(false, false), availability(false).status());
    }

    @Test
    void switchedOnMeansAvailable() {
        assertTrue(availability(true).isAvailable());
    }

    @Test
    void requiredFollowsTheSettingWhateverTheAvailability() {
        setting.setRequireIdentityVerification(true);

        assertEquals(new IdentityStatusResponse(false, true), availability(false).status());
        assertEquals(new IdentityStatusResponse(true, true), availability(true).status());
    }

    @Test
    void aMissingSettingsRowMeansNotRequired() {
        when(settingRepository.findById(1L)).thenReturn(Optional.empty());

        assertFalse(availability(true).isRequired());
    }
}
