package com.coopaggregate.identity;

/**
 * What a verifier returns: the provider's pseudonymous id for the person (the same person always gets the same
 * id, but it is not their ID number) and how they were checked.
 */
public record VerifiedIdentity(String subject, VerificationMethod method) {

    // Records print their fields; the subject must never end up in a log line.
    @Override
    public String toString() {
        return "VerifiedIdentity[subject=****, method=" + method + "]";
    }
}
