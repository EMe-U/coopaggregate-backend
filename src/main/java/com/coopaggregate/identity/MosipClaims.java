package com.coopaggregate.identity;

/**
 * What the provider says about the person, kept in memory only for the moment of the callback. subject is the pairwise
 * id (not a national ID number). The other claims are compared with the member's details and never stored or logged.
 */
public record MosipClaims(String subject, String name, String birthdate, String phoneNumber, String email) {

    // Records print their fields; the claims must never end up in a log line.
    @Override
    public String toString() {
        return "MosipClaims[****]";
    }
}
