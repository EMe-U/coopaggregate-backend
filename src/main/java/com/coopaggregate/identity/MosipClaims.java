package com.coopaggregate.identity;

import java.time.LocalDate;

/**
 * What the provider says about the person, kept in memory only for the moment of the callback. subject is the pairwise
 * id (not a national ID number). name and phoneNumber are essential; birthdate, gender, email and address are optional
 * and null when the member did not allow them, they were not asked for, or their value did not fit. Anything else
 * eSignet sends (a picture, given_name) is never read. acr is the id_token's "acr" (how the person logged in), null
 * when the id_token has none.
 */
public record MosipClaims(String subject, String name, String phoneNumber, String acr, LocalDate birthdate,
                          String gender, String email, String address) {

    /** Claims from an id_token without an acr and with no optional details. */
    public MosipClaims(String subject, String name, String phoneNumber) {
        this(subject, name, phoneNumber, null);
    }

    /** Claims with no optional details. */
    public MosipClaims(String subject, String name, String phoneNumber, String acr) {
        this(subject, name, phoneNumber, acr, null, null, null, null);
    }

    // Records print their fields; the claims must never end up in a log line.
    @Override
    public String toString() {
        return "MosipClaims[****]";
    }
}
