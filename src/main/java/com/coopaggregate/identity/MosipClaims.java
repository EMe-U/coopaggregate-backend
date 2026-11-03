package com.coopaggregate.identity;

/**
 * What the provider says about the person, kept in memory only for the moment of the callback. subject is the pairwise
 * id (not a national ID number); name and phoneNumber are the only details asked for. Anything else eSignet sends (a
 * birth date, an email) is never read.
 */
public record MosipClaims(String subject, String name, String phoneNumber) {

    // Records print their fields; the claims must never end up in a log line.
    @Override
    public String toString() {
        return "MosipClaims[****]";
    }
}
