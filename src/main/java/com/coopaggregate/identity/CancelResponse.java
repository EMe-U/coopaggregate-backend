package com.coopaggregate.identity;

/**
 * How a verification that came back without a code ended, with a message the frontend can show (or replace with its
 * own text for the reason). Nothing was saved in any case.
 */
public record CancelResponse(Reason reason, String message) {

    public enum Reason {
        // access_denied: the member stopped at eSignet.
        CANCELLED("cancelled at the provider",
                "The member cancelled the verification at eSignet. Nothing was saved."),
        // essential_consent_denied: the member did not allow the name or phone.
        CLAIMS_REFUSED("the member did not allow the name and phone",
                "The member did not allow eSignet to share their name and phone number, which are needed to register "
                        + "them. Nothing was saved."),
        // Any other error, or one that is not known.
        PROVIDER_ERROR("the provider returned an error",
                "eSignet could not complete the verification. Nothing was saved; try again.");

        private final String history;
        private final String message;

        Reason(String history, String message) {
            this.history = history;
            this.message = message;
        }

        String history() {
            return history;
        }
    }

    static Reason reasonFor(String knownCode) {
        if ("essential_consent_denied".equals(knownCode)) {
            return Reason.CLAIMS_REFUSED;
        }
        if ("access_denied".equals(knownCode)) {
            return Reason.CANCELLED;
        }
        return Reason.PROVIDER_ERROR;
    }

    static CancelResponse of(Reason reason) {
        return new CancelResponse(reason, reason.message);
    }
}
