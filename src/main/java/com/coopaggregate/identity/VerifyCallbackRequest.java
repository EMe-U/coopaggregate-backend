package com.coopaggregate.identity;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What the provider sent back to the redirect URI. */
public record VerifyCallbackRequest(
        @NotBlank(message = "is required")
        @Size(max = 2000, message = "must be at most 2000 characters")
        String code,

        @NotBlank(message = "is required")
        @Size(max = 100, message = "must be at most 100 characters")
        String state) {

    // Records print their fields; the code and state must never end up in a log line.
    @Override
    public String toString() {
        return "VerifyCallbackRequest[code=****, state=****]";
    }
}
