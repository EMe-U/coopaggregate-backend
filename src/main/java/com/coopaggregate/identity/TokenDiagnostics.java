package com.coopaggregate.identity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.web.client.RestOperations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Explains, for the server log only, why an id_token or userinfo answer was refused. It runs only after a check failed
 * and changes nothing about what is accepted. It gives the HTTP status and Content-Type of the answer, what the body
 * looks like, the decoded header, the NAMES of the claims (values only for iss, aud, exp and iat), whether sub is the
 * id_token's, the keys eSignet publishes, and which of our own checks fail. Never a claim value such as a name or
 * phone, the access token, the signature or the whole token, and never an exception's message.
 */
final class TokenDiagnostics {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_VALUE = 200;

    /** What came back from the userinfo call; for the id_token there is none. */
    record Response(int status, String contentType) {
    }

    private final RestOperations jwksOperations;
    private final String jwksUrl;
    private final String issuer;
    private final String clientId;
    private final Clock clock;
    private final Duration skew;

    TokenDiagnostics(RestOperations jwksOperations, String jwksUrl, String issuer, String clientId, Clock clock,
                     Duration skew) {
        this.jwksOperations = jwksOperations;
        this.jwksUrl = jwksUrl;
        this.issuer = issuer;
        this.clientId = clientId;
        this.clock = clock;
        this.skew = skew;
    }

    /**
     * category is the class of the exception that refused the token (null when our own code refused it); expectedSub
     * is the id_token's sub, for the userinfo answer only.
     */
    String describe(String body, Response response, String expectedSub, String category) {
        StringBuilder text = new StringBuilder();
        List<String> failed = new ArrayList<>();
        String trimmed = body == null ? "" : body.trim();
        String kind = kind(trimmed);
        JWKSet keys = keys();

        Map<String, Object> claims = null;
        String header = null;
        if (kind.equals("JWT")) {
            try {
                SignedJWT jwt = SignedJWT.parse(trimmed);
                JWSHeader h = jwt.getHeader();
                header = "{alg=" + h.getAlgorithm() + ", kid=" + safe(h.getKeyID()) + ", typ="
                        + (h.getType() == null ? "(none)" : safe(h.getType().toString())) + "}";
                if (!JWSAlgorithm.PS256.equals(h.getAlgorithm())) {
                    failed.add("algorithm not allowed");
                } else {
                    failed.addAll(signature(jwt, keys));
                }
                claims = jwt.getJWTClaimsSet().getClaims();
            } catch (java.text.ParseException | RuntimeException e) {
                failed.add("format: not a readable JWT");
            }
        } else if (kind.equals("JSON")) {
            failed.add("format: JSON, not a signed JWT");
            claims = jsonClaims(trimmed);
        } else {
            failed.add("format: not a JWT");
        }
        if (claims != null) {
            failed.addAll(claimChecks(claims, expectedSub));
        }

        text.append("failedChecks=").append(failed.isEmpty() ? "[none of ours]" : failed);
        text.append(", category=").append(category == null ? "(our own check)" : category);
        if (response != null) {
            text.append("; response HTTP ").append(response.status()).append(", Content-Type ")
                    .append(response.contentType() == null ? "(none)" : safe(response.contentType()));
        }
        text.append("; body ").append(kind);
        if (header != null) {
            text.append("; header ").append(header);
        }
        if (claims != null) {
            text.append("; claimNames ").append(new TreeSet<>(claims.keySet()).stream().map(TokenDiagnostics::safe)
                    .toList());
            text.append("; iss=").append(value(claims.get("iss"))).append(", aud=").append(value(claims.get("aud")))
                    .append(", exp=").append(time(claims.get("exp"))).append(", iat=").append(time(claims.get("iat")));
            if (expectedSub != null) {
                text.append("; subMatchesIdToken=").append(expectedSub.equals(text(claims.get("sub"))));
            }
        }
        text.append("; jwks ").append(keys == null ? "(could not be read)" : describeKeys(keys));
        return text.toString();
    }

    private static String kind(String body) {
        if (body.isEmpty()) {
            return "empty";
        }
        if (body.startsWith("{")) {
            return "JSON";
        }
        int parts = body.split("\\.", -1).length;
        if (parts == 3) {
            return "JWT";
        }
        return parts == 5 ? "encrypted JWT (5 parts)" : "other";
    }

    // The key the header names (or, without a kid, any RSA key) must verify the signature.
    private static List<String> signature(SignedJWT jwt, JWKSet keys) {
        if (keys == null) {
            return List.of("signature: keys could not be read");
        }
        String kid = jwt.getHeader().getKeyID();
        List<JWK> candidates = keys.getKeys().stream()
                .filter(key -> key instanceof RSAKey)
                .filter(key -> kid == null || kid.equals(key.getKeyID()))
                .toList();
        if (candidates.isEmpty()) {
            return List.of("signature: no matching key");
        }
        for (JWK key : candidates) {
            try {
                if (jwt.verify(new RSASSAVerifier(((RSAKey) key).toRSAPublicKey()))) {
                    return List.of();
                }
            } catch (com.nimbusds.jose.JOSEException | RuntimeException e) {
                // Try the next key.
            }
        }
        return List.of("signature: does not verify");
    }

    private List<String> claimChecks(Map<String, Object> claims, String expectedSub) {
        List<String> failed = new ArrayList<>();
        Object iss = claims.get("iss");
        if (iss == null) {
            failed.add("iss missing");
        } else if (!issuer.equals(text(iss))) {
            failed.add("iss different");
        }
        Object aud = claims.get("aud");
        if (aud == null) {
            failed.add("aud missing");
        } else if (!audiences(aud).contains(clientId)) {
            failed.add("aud different");
        }
        Instant now = clock.instant();
        Instant exp = instant(claims.get("exp"));
        if (claims.get("exp") == null) {
            failed.add("exp missing");
        } else if (exp == null) {
            failed.add("exp unreadable");
        } else if (exp.plus(skew).isBefore(now)) {
            failed.add("expired");
        }
        Instant nbf = instant(claims.get("nbf"));
        if (nbf != null && nbf.minus(skew).isAfter(now)) {
            failed.add("not yet valid");
        }
        String sub = text(claims.get("sub"));
        if (sub == null || sub.isBlank()) {
            failed.add("sub missing");
        } else if (expectedSub != null && !expectedSub.equals(sub)) {
            failed.add("sub mismatch");
        }
        return failed;
    }

    private JWKSet keys() {
        try {
            String body = jwksOperations.getForObject(jwksUrl, String.class);
            return body == null ? null : JWKSet.parse(body);
        } catch (java.text.ParseException | RuntimeException e) {
            return null;
        }
    }

    private static String describeKeys(JWKSet keys) {
        return keys.getKeys().stream()
                .map(key -> "{kid=" + safe(key.getKeyID()) + ", alg=" + (key.getAlgorithm() == null ? "(none)"
                        : safe(key.getAlgorithm().getName())) + ", kty=" + key.getKeyType() + "}")
                .toList().toString();
    }

    // Top-level names of a JSON answer, and its claims for the checks; anything unreadable gives none.
    private static Map<String, Object> jsonClaims(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (!node.isObject()) {
                return null;
            }
            Map<String, Object> claims = new java.util.HashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                JsonNode value = field.getValue();
                claims.put(field.getKey(), value.isTextual() ? value.asText()
                        : value.isNumber() ? value.numberValue()
                        : value.isArray() ? JSON.convertValue(value, List.class) : value.toString());
            }
            return claims;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> audiences(Object aud) {
        if (aud instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of(String.valueOf(aud));
    }

    private static Instant instant(Object value) {
        if (value instanceof Date date) {
            return date.toInstant();
        }
        if (value instanceof Number number) {
            return Instant.ofEpochSecond(number.longValue());
        }
        return null;
    }

    private static String time(Object value) {
        if (value == null) {
            return "(none)";
        }
        Instant instant = instant(value);
        return instant == null ? "(not a time)" : instant.getEpochSecond() + " (" + instant + ")";
    }

    // Only for iss and aud: the issuer's address and client ids, never personal data.
    private static String value(Object value) {
        if (value == null) {
            return "(none)";
        }
        return value instanceof List<?> list ? list.stream().map(item -> safe(String.valueOf(item))).toList()
                .toString() : safe(String.valueOf(value));
    }

    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }

    // One line, at most 200 characters, so nothing can forge or flood a log line.
    private static String safe(String value) {
        if (value == null) {
            return "(none)";
        }
        String clean = value.replaceAll("[\\p{Cntrl}]", "?");
        return clean.length() > MAX_VALUE ? clean.substring(0, MAX_VALUE) + "..." : clean;
    }
}
