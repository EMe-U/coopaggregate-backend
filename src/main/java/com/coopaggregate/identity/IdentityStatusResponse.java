package com.coopaggregate.identity;

/**
 * available: the identity provider is switched on and set up. required: the cooperative requires a verified identity
 * to register a member. Required but not available means registration is blocked until the setting is switched off.
 * unverifiedAllowed: the provider is off and MEMBER_REGISTER_UNVERIFIED=true, so a member can be registered with a
 * typed name and phone and is stored as not verified (the frontend shows the plain form).
 */
public record IdentityStatusResponse(boolean available, boolean required, boolean unverifiedAllowed) {
}
