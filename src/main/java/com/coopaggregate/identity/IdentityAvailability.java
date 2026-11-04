package com.coopaggregate.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Whether the identity provider is switched on (MOSIP_ENABLED). When it is on the app has already checked its settings
 * and its key at startup (see IdentityConfig), so on means ready; a provider that is down is handled when it is called.
 * Registering a member needs a verification while the provider is on. With the provider off, members cannot be
 * registered, unless MEMBER_REGISTER_UNVERIFIED=true: then they are registered with a typed name and phone and stored
 * as not verified. That setting is ignored while the provider is on, even when the provider cannot be reached.
 */
@Component
public class IdentityAvailability {

    private static final Logger log = LoggerFactory.getLogger(IdentityAvailability.class);

    private final MosipProperties properties;
    private final boolean registerUnverified;

    public IdentityAvailability(MosipProperties properties,
                                @Value("${app.members.register-unverified:false}") boolean registerUnverified) {
        this.properties = properties;
        this.registerUnverified = registerUnverified;
        if (registerUnverified && properties.enabled()) {
            log.warn("MEMBER_REGISTER_UNVERIFIED=true is ignored because MOSIP_ENABLED=true: every member is verified");
        } else if (registerUnverified) {
            log.warn("MEMBER_REGISTER_UNVERIFIED=true and MOSIP_ENABLED=false: members are registered WITHOUT identity "
                    + "verification and stored as not verified. For demos and fallbacks only.");
        }
    }

    public boolean isAvailable() {
        return properties.enabled();
    }

    public boolean isRequired() {
        return isAvailable();
    }

    /** Only when the provider is switched off and MEMBER_REGISTER_UNVERIFIED=true. */
    public boolean isUnverifiedAllowed() {
        return !properties.enabled() && registerUnverified;
    }

    public IdentityStatusResponse status() {
        return new IdentityStatusResponse(isAvailable(), isRequired(), isUnverifiedAllowed());
    }
}
