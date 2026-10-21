package com.coopaggregate.delivery;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ConfirmCodeRequest(
        @NotNull(message = "is required")
        @Pattern(regexp = "\\d{6}", message = "must be 6 digits")
        String code) {

    // Records print their fields; the code must never end up in a log line.
    @Override
    public String toString() {
        return "ConfirmCodeRequest[code=******]";
    }
}
