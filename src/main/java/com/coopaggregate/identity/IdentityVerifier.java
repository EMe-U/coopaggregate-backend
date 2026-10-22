package com.coopaggregate.identity;

/**
 * Checks a new member's identity with an identity provider: MockIdentityVerifier for development,
 * MosipIdentityVerifier (MOSIP eSignet) when MOSIP_ENABLED=true.
 */
public interface IdentityVerifier {

    /**
     * Where to send the member to prove who they are. The provider sends them back with a code and the same state.
     * Null when no redirect is needed (the mock).
     */
    String authorizationUrl(String state, String nonce);

    /** Exchanges the code from the provider for the person's pseudonymous id. Throws IdentityProviderException. */
    VerifiedIdentity complete(String code, String nonce);

    /** Short name returned to the frontend, for example "MOCK" or "MOSIP". */
    String name();
}
