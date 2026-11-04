package com.coopaggregate.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What eSignet sent back to the redirect URI instead of a code: the state and the error code. eSignet's free-text
 * error_description is not sent: it is never stored or shown.
 */
public record VerifyCancelRequest(
        @NotBlank(message = "is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String state,

        @Size(max = 100, message = "must be at most 100 characters")
        String error) {

    // Records print their fields; the state must never end up in a log line.
    @Override
    public String toString() {
        return "VerifyCancelRequest[state=****, error=" + error + "]";
    }
}
