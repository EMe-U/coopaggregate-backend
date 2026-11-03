package com.coopaggregate.identity;

/** The verified name and phone (+2507XXXXXXXX) a new member is registered with. */
public record VerifiedDetails(String fullName, String phone) {

    // Records print their fields; the details must never end up in a log line.
    @Override
    public String toString() {
        return "VerifiedDetails[****]";
    }
}
