package com.coopaggregate.identity;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every MOSIP eSignet setting is here and nowhere else. The values come only from environment variables, never
 * from code or Git.
 *
 * TO CONFIRM WITH THE SUPERVISOR (the sandbox details are not known yet), so these have no defaults:
 * - authorizationEndpoint and tokenEndpoint: the full URLs of eSignet's authorize and token endpoints,
 * - scope and acrValues: which scopes and authentication methods (OTP, face, ...) the client must ask for,
 * - the format of MOSIP_PRIVATE_KEY (PEM or JWK) and the algorithm used to sign the client assertion,
 * - which claim of the ID token says how the person was checked (for OTP or FACE).
 */
@ConfigurationProperties("app.identity.mosip")
public record MosipProperties(
        boolean enabled,
        String baseUrl,
        String clientId,
        String redirectUri,
        String privateKey,
        String authorizationEndpoint,
        String tokenEndpoint,
        String scope,
        String acrValues) {

    /** The environment variables that MOSIP_ENABLED=true needs but are empty. Names only, never values. */
    public List<String> missingRequired() {
        List<String> missing = new ArrayList<>();
        if (isBlank(baseUrl)) {
            missing.add("MOSIP_BASE_URL");
        }
        if (isBlank(clientId)) {
            missing.add("MOSIP_CLIENT_ID");
        }
        if (isBlank(redirectUri)) {
            missing.add("MOSIP_REDIRECT_URI");
        }
        if (isBlank(privateKey)) {
            missing.add("MOSIP_PRIVATE_KEY");
        }
        return missing;
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // Records print their fields; the private key must never end up in a log line.
    @Override
    public String toString() {
        return "MosipProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", clientId=" + clientId
                + ", redirectUri=" + redirectUri + ", privateKey=" + (isBlank(privateKey) ? "(empty)" : "****")
                + ", authorizationEndpoint=" + authorizationEndpoint + ", tokenEndpoint=" + tokenEndpoint
                + ", scope=" + scope + ", acrValues=" + acrValues + "]";
    }
}
