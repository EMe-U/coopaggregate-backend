package com.coopaggregate.identity;

import org.springframework.stereotype.Component;

import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

/**
 * Whether the identity provider is switched on. When it is on the app has already checked its settings and its key
 * at startup (see IdentityConfig), so on means ready; a provider that is down is handled when it is called.
 */
@Component
public class IdentityAvailability {

    private final MosipProperties properties;
    private final SettingRepository settingRepository;

    public IdentityAvailability(MosipProperties properties, SettingRepository settingRepository) {
        this.properties = properties;
        this.settingRepository = settingRepository;
    }

    public boolean isAvailable() {
        return properties.enabled();
    }

    /** Whether registering a member needs a verification. Only the setting says so; availability is separate. */
    public boolean isRequired() {
        return settingRepository.findById(1L).map(Setting::isRequireIdentityVerification).orElse(false);
    }

    public IdentityStatusResponse status() {
        return new IdentityStatusResponse(isAvailable(), isRequired());
    }
}
