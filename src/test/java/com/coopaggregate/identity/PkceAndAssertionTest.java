package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;

class PkceAndAssertionTest {

    private static KeyPair keyPair;

    @BeforeAll
    static void createKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    // --- PKCE

    @Test
    void theChallengeIsTheS256HashOfTheVerifierLikeInRfc7636() {
        // The example of RFC 7636, appendix B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
                Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
    }

    @Test
    void aVerifierIs43UrlSafeCharactersAndNeverRepeats() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String verifier = Pkce.newVerifier();

            assertEquals(43, verifier.length());
            assertTrue(verifier.matches("[A-Za-z0-9_-]{43}"), verifier);
            assertTrue(seen.add(verifier));
        }
    }

    // --- Client assertion

    @Test
    void theAssertionIsSignedWithPs256AndCarriesTheKeyIdAndTheClaimsEsignetChecks() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneOffset.UTC);

        SignedJWT jwt = SignedJWT.parse(MosipClientAssertion.create(properties(), signingKey(), clock));

        assertEquals(JWSAlgorithm.PS256, jwt.getHeader().getAlgorithm());
        assertEquals("coopaggregate-key", jwt.getHeader().getKeyID());
        assertTrue(jwt.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic())), "signed with our private key");
        assertEquals("coopaggregate", jwt.getJWTClaimsSet().getIssuer());
        assertEquals("coopaggregate", jwt.getJWTClaimsSet().getSubject());
        assertEquals(java.util.List.of("http://localhost:8080"), jwt.getJWTClaimsSet().getAudience());
        assertEquals(Instant.parse("2026-10-31T10:00:00Z"), jwt.getJWTClaimsSet().getIssueTime().toInstant());
        assertEquals(Instant.parse("2026-10-31T10:01:00Z"), jwt.getJWTClaimsSet().getExpirationTime().toInstant());
    }

    @Test
    void theAudienceCanBeSetApartFromTheIssuer() throws Exception {
        MosipProperties properties = new MosipProperties(true, "http://localhost:8080", "coopaggregate", "r", "k",
                "coopaggregate-key", null, null, "http://localhost:8080/oauth2/token", null, null, null, null);

        SignedJWT jwt = SignedJWT.parse(MosipClientAssertion.create(properties, signingKey(), Clock.systemUTC()));

        assertEquals(java.util.List.of("http://localhost:8080/oauth2/token"), jwt.getJWTClaimsSet().getAudience());
    }

    @Test
    void everyAssertionIsNew() throws Exception {
        String first = SignedJWT.parse(MosipClientAssertion.create(properties(), signingKey(), Clock.systemUTC()))
                .getJWTClaimsSet().getJWTID();
        String second = SignedJWT.parse(MosipClientAssertion.create(properties(), signingKey(), Clock.systemUTC()))
                .getJWTClaimsSet().getJWTID();

        assertNotEquals(first, second);
    }

    private static MosipProperties properties() {
        return new MosipProperties(true, "http://localhost:8080", "coopaggregate", "r", "k", "coopaggregate-key",
                null, null, null, null, null, null, null);
    }

    private static MosipSigningKey signingKey() {
        return new MosipSigningKey((RSAPrivateKey) keyPair.getPrivate(), "coopaggregate-key");
    }
}
