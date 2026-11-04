package com.coopaggregate.identity;

/**
 * What the provider says about the person, kept in memory only for the moment of the callback. subject is the pairwise
 * id (not a national ID number); name and phoneNumber are the only details asked for. Anything else eSignet sends (a
 * birth date, an email) is never read. acr is the id_token's "acr" (how the person logged in), null when the id_token
 * has none.
 */
public record MosipClaims(String subject, String name, String phoneNumber, String acr) {

    /** Claims from an id_token without an acr. */
    public MosipClaims(String subject, String name, String phoneNumber) {
        this(subject, name, phoneNumber, null);
    }

    // Records print their fields; the claims must never end up in a log line.
    @Override
    public String toString() {
        return "MosipClaims[****]";
    }
}
