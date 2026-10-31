package com.coopaggregate.identity;

/**
 * The identity provider could not verify the person: it is not configured yet (503) or it failed (502). The message
 * never contains codes, tokens or keys.
 */
public class IdentityProviderException extends RuntimeException {

    private final boolean notConfigured;

    private IdentityProviderException(String message, boolean notConfigured) {
        super(message);
        this.notConfigured = notConfigured;
    }

    public static IdentityProviderException notConfigured(String message) {
        return new IdentityProviderException(message, true);
    }

    public static IdentityProviderException failed(String message) {
        return new IdentityProviderException(message, false);
    }

    public boolean isNotConfigured() {
        return notConfigured;
    }
}
