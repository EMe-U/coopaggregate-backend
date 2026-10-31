package com.coopaggregate.identity;

/**
 * available: the identity provider is switched on and set up. required: the cooperative requires a verified identity
 * to register a member. Required but not available means registration is blocked until the setting is switched off.
 */
public record IdentityStatusResponse(boolean available, boolean required) {
}
