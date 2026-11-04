package com.coopaggregate.identity;

import org.springframework.stereotype.Component;

/**
 * Whether the identity provider is switched on (MOSIP_ENABLED). When it is on the app has already checked its settings
 * and its key at startup (see IdentityConfig), so on means ready; a provider that is down is handled when it is called.
 * Registering a member always needs a verification, so required is the same as available: with the provider off,
 * members cannot be registered.
 */
@Component
public class IdentityAvailability {

    private final MosipProperties properties;

    public IdentityAvailability(MosipProperties properties) {
        this.properties = properties;
    }

    public boolean isAvailable() {
        return properties.enabled();
    }

    public boolean isRequired() {
        return isAvailable();
    }

    public IdentityStatusResponse status() {
        return new IdentityStatusResponse(isAvailable(), isRequired());
    }
}
