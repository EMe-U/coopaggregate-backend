package com.coopaggregate.identity;

import java.security.interfaces.RSAPrivateKey;

/** The cooperative's RSA private key and the id (kid) of its public JWK. Only the key object is kept, never the file. */
public final class MosipSigningKey {

    private final RSAPrivateKey privateKey;
    private final String keyId;

    public MosipSigningKey(RSAPrivateKey privateKey, String keyId) {
        this.privateKey = privateKey;
        this.keyId = keyId;
    }

    public RSAPrivateKey privateKey() {
        return privateKey;
    }

    public String keyId() {
        return keyId;
    }

    @Override
    public String toString() {
        return "MosipSigningKey[keyId=" + keyId + ", privateKey=****]";
    }
}
