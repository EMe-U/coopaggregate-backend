package com.coopaggregate.identity;

import java.security.Key;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.KeySourceException;

/**
 * Picks the key for the userinfo signature: only for an allowed algorithm (PS256 or RS256), only the RSA key of
 * eSignet's JWKS whose kid is the one in the token header. The "alg" the JWKS lists for that key is not compared:
 * eSignet lists PS256 for the key it also signs userinfo with using RS256. No kid, or no such key, gives no key, so the
 * token is refused.
 */
final class UserinfoKeySelector implements JWSKeySelector<SecurityContext> {

    private final Set<JWSAlgorithm> algorithms;
    private final JWKSource<SecurityContext> source;

    UserinfoKeySelector(Set<JWSAlgorithm> algorithms, JWKSource<SecurityContext> source) {
        this.algorithms = algorithms;
        this.source = source;
    }

    @Override
    public List<? extends Key> selectJWSKeys(JWSHeader header, SecurityContext context) throws KeySourceException {
        if (!algorithms.contains(header.getAlgorithm()) || header.getKeyID() == null) {
            return List.of();
        }
        JWKMatcher matcher = new JWKMatcher.Builder()
                .keyType(KeyType.RSA)
                .keyID(header.getKeyID())
                .keyUses(KeyUse.SIGNATURE, null)
                .build();
        List<Key> keys = new ArrayList<>();
        for (JWK key : source.get(new JWKSelector(matcher), context)) {
            try {
                keys.add(((RSAKey) key).toRSAPublicKey());
            } catch (JOSEException | ClassCastException e) {
                // Not a usable RSA key: skip it.
            }
        }
        return keys;
    }
}
