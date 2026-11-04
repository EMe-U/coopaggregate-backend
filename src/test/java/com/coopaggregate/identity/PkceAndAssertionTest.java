package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void theSignatureIsRsassaPssWithSha256Mgf1Sha256AndA32ByteSalt() throws Exception {
        SignedJWT jwt = SignedJWT.parse(MosipClientAssertion.create(properties(), signingKey(), Clock.systemUTC()));
        byte[] signingInput = jwt.getSigningInput();
        byte[] signature = jwt.getSignature().decode();

        assertEquals(JWSAlgorithm.PS256, jwt.getHeader().getAlgorithm());
        assertTrue(pss(32, signingInput, signature), "RSASSA-PSS, SHA-256, MGF1-SHA-256, 32-byte salt");
        // The same signature does not verify with another salt length or hash, so the parameters are exactly these.
        assertFalse(pss(20, signingInput, signature));
        assertFalse(pss(64, signingInput, signature));
        java.security.Signature sha384 = java.security.Signature.getInstance("RSASSA-PSS");
        sha384.setParameter(new java.security.spec.PSSParameterSpec("SHA-384", "MGF1",
                java.security.spec.MGF1ParameterSpec.SHA384, 48, 1));
        sha384.initVerify(keyPair.getPublic());
        sha384.update(signingInput);
        assertFalse(sha384.verify(signature));
        // And it verifies against the public key with a JOSE library too.
        assertTrue(jwt.verify(new RSASSAVerifier((RSAPublicKey) keyPair.getPublic())));
    }

    // Verifies with the JDK's own RSASSA-PSS: SHA-256, MGF1 with SHA-256, the given salt length, trailer field 1.
    private static boolean pss(int saltLength, byte[] signingInput, byte[] signature) throws Exception {
        java.security.Signature verifier = java.security.Signature.getInstance("RSASSA-PSS");
        verifier.setParameter(new java.security.spec.PSSParameterSpec("SHA-256", "MGF1",
                java.security.spec.MGF1ParameterSpec.SHA256, saltLength, 1));
        verifier.initVerify(keyPair.getPublic());
        verifier.update(signingInput);
        return verifier.verify(signature);
    }

    @Test
    void theAudienceCanBeSetApartFromTheIssuer() throws Exception {
        MosipProperties properties = new MosipProperties(true, "http://localhost:8080", "coopaggregate", "r", "k",
                "coopaggregate-key", null, null, "http://localhost:8080/oauth2/token", null, null, null, null, null, null, null, null, null, false);

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
                null, null, null, null, null, null, null, null, null, null, null, null, false);
    }

    private static MosipSigningKey signingKey() {
        return new MosipSigningKey((RSAPrivateKey) keyPair.getPrivate(), "coopaggregate-key");
    }
}
