package com.coopaggregate.identity;

/**
 * The identity provider could not verify the person: it is not configured yet (503) or it failed (502). The message
 * never contains codes, tokens or keys, and is what the client gets. The detail is for the server log only: where
 * the call went and how it failed (see Diagnostics); it is never part of the message or of any response.
 */
public class IdentityProviderException extends RuntimeException {

    private final boolean notConfigured;
    private final String detail;

    private IdentityProviderException(String message, boolean notConfigured, String detail) {
        super(message);
        this.notConfigured = notConfigured;
        this.detail = detail;
    }

    public static IdentityProviderException notConfigured(String message) {
        return new IdentityProviderException(message, true, null);
    }

    public static IdentityProviderException failed(String message) {
        return new IdentityProviderException(message, false, null);
    }

    public static IdentityProviderException failed(String message, String detail) {
        return new IdentityProviderException(message, false, detail);
    }

    /** Where the call went and how it failed, for the log. Null when there is nothing to add. */
    public String getDetail() {
        return detail;
    }

    public boolean isNotConfigured() {
        return notConfigured;
    }
}
