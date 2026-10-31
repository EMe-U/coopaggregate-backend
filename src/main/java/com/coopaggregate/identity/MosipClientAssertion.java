package com.coopaggregate.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * The client assertion of private_key_jwt: a short-lived JWT, signed with the cooperative's private key (PS256, with
 * the kid of its public JWK), that proves to the token endpoint who is asking. It is new for every request.
 */
final class MosipClientAssertion {

    static final long VALID_SECONDS = 60;

    private MosipClientAssertion() {
    }

    static String create(MosipProperties properties, MosipSigningKey key, Clock clock) {
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(properties.clientId())
                .subject(properties.clientId())
                .audience(properties.audience())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(VALID_SECONDS)))
                .jwtID(UUID.randomUUID().toString())
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.PS256).keyID(key.keyId()).build(), claims);
        try {
            jwt.sign(new RSASSASigner(key.privateKey()));
        } catch (JOSEException e) {
            // The message is left out: it is about the key.
            throw IdentityProviderException.failed("The request to the identity provider could not be signed.");
        }
        return jwt.serialize();
    }
}
