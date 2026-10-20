package com.coopaggregate.member;

import jakarta.validation.constraints.NotNull;

public record PinRequest(@NotNull(message = "is required") String pin) {

    // Records print their fields; the PIN must never end up in a log line.
    @Override
    public String toString() {
        return "PinRequest[pin=****]";
    }
}
