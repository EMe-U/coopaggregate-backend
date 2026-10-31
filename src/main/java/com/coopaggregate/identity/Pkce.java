package com.coopaggregate.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** PKCE (RFC 7636) with the S256 method: the verifier stays here, only its hash goes to the provider first. */
final class Pkce {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Pkce() {
    }

    /** 32 random bytes as 43 URL-safe characters, the shortest verifier the RFC allows. */
    static String newVerifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String challenge(String verifier) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
