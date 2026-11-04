package com.coopaggregate.identity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.ParseException;
import java.util.Base64;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;

/**
 * Reads the RSA private key from a file, either a PEM (PKCS#8, "BEGIN PRIVATE KEY") or a JWK with its private parts.
 * The format is detected from the content. Errors never include the content or the path.
 */
final class MosipKeyLoader {

    static final int MIN_BITS = 2048;

    private MosipKeyLoader() {
    }

    static RSAPrivateKey load(String path) {
        String content;
        try {
            content = Files.readString(Path.of(path), StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException e) {
            throw unusable("the file cannot be read");
        }
        RSAPrivateKey key;
        if (content.startsWith("{")) {
            key = fromJwk(content);
        } else if (content.contains("-----BEGIN PRIVATE KEY-----")) {
            key = fromPem(content);
        } else if (content.contains("-----BEGIN RSA PRIVATE KEY-----")) {
            throw unusable("the PEM is in the old PKCS#1 format; convert it to PKCS#8 (BEGIN PRIVATE KEY)");
        } else {
            throw unusable("it is neither a PEM (PKCS#8) nor a JWK");
        }
        if (key.getModulus().bitLength() < MIN_BITS) {
            throw unusable("the key is shorter than " + MIN_BITS + " bits");
        }
        return key;
    }

    private static RSAPrivateKey fromJwk(String json) {
        try {
            RSAKey jwk = RSAKey.parse(json);
            if (!jwk.isPrivate()) {
                throw unusable("the JWK holds only the public key");
            }
            return jwk.toRSAPrivateKey();
        } catch (ParseException | JOSEException | ClassCastException e) {
            throw unusable("it is not a valid RSA JWK with a private key");
        }
    }

    private static RSAPrivateKey fromPem(String pem) {
        try {
            String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            return (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw unusable("it is not a valid RSA private key in PKCS#8 PEM format");
        }
    }

    private static IllegalStateException unusable(String reason) {
        return new IllegalStateException("MOSIP_PRIVATE_KEY_PATH does not point to a usable RSA private key: "
                + reason + ".");
    }
}
