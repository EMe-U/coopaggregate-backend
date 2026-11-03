package com.coopaggregate.identity;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Every eSignet setting is here and nowhere else. The values come only from environment variables, never from code
 * or Git. The four endpoints are derived from baseUrl (eSignet's /oauth2/... paths) unless they are set.
 */
@ConfigurationProperties("app.identity.mosip")
public record MosipProperties(
        boolean enabled,
        String baseUrl,
        String clientId,
        String redirectUri,
        String privateKeyPath,
        String keyId,
        String scope,
        String acrValues,
        String assertionAudience,
        String authorizationEndpoint,
        String tokenEndpoint,
        String userinfoEndpoint,
        String jwksUri) {

    // Only openid: the name and phone are asked for with the claims parameter (see MosipClient), so no scope brings
    // in details the cooperative does not use.
    static final String DEFAULT_SCOPE = "openid";
    private static final List<String> NEEDED_SCOPES = List.of("openid", "phone");
    static final String DEFAULT_ACR = "mosip:idp:acr:password";

    public MosipProperties {
        baseUrl = isBlank(baseUrl) ? null : baseUrl.trim().replaceAll("/+$", "");
        scope = isBlank(scope) ? DEFAULT_SCOPE : scope.trim();
        acrValues = isBlank(acrValues) ? DEFAULT_ACR : acrValues.trim();
    }

    /** The environment variables that MOSIP_ENABLED=true needs but are empty. Names only, never values. */
    public List<String> missingRequired() {
        List<String> missing = new ArrayList<>();
        if (baseUrl == null) {
            missing.add("MOSIP_BASE_URL");
        }
        if (isBlank(clientId)) {
            missing.add("MOSIP_CLIENT_ID");
        }
        if (isBlank(redirectUri)) {
            missing.add("MOSIP_REDIRECT_URI");
        }
        if (isBlank(privateKeyPath)) {
            missing.add("MOSIP_PRIVATE_KEY_PATH");
        }
        if (isBlank(keyId)) {
            missing.add("MOSIP_KEY_ID");
        }
        return missing;
    }

    /** Scopes in MOSIP_SCOPE beyond openid (and phone), which would bring details that are never used. */
    public List<String> extraScopes() {
        return java.util.Arrays.stream(scope.split("\\s+"))
                .filter(name -> !name.isEmpty() && !NEEDED_SCOPES.contains(name))
                .toList();
    }

    /** Settings that are present but unsafe: plain http is only for a local eSignet. Names only. */
    public List<String> insecure() {
        List<String> insecure = new ArrayList<>();
        if (baseUrl != null && !isHttpsOrLocal(baseUrl)) {
            insecure.add("MOSIP_BASE_URL");
        }
        if (!isBlank(redirectUri) && !isHttpsOrLocal(redirectUri)) {
            insecure.add("MOSIP_REDIRECT_URI");
        }
        return insecure;
    }

    /** The issuer the ID token and the userinfo response must name. */
    public String issuer() {
        return baseUrl;
    }

    public String authorizationUrl() {
        return endpoint(authorizationEndpoint, "/oauth2/authorize");
    }

    public String tokenUrl() {
        return endpoint(tokenEndpoint, "/oauth2/token");
    }

    public String userinfoUrl() {
        return endpoint(userinfoEndpoint, "/oauth2/userinfo");
    }

    public String jwksUrl() {
        return endpoint(jwksUri, "/oauth2/jwks");
    }

    /** The "aud" of the client assertion: the issuer unless MOSIP_ASSERTION_AUDIENCE says otherwise. */
    public String audience() {
        return isBlank(assertionAudience) ? baseUrl : assertionAudience.trim();
    }

    private String endpoint(String configured, String path) {
        return isBlank(configured) ? baseUrl + path : configured.trim();
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isHttpsOrLocal(String url) {
        try {
            URI uri = URI.create(url.trim());
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return true;
            }
            String host = uri.getHost();
            return "http".equalsIgnoreCase(uri.getScheme())
                    && ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // Records print their fields; keep the key file's path out of any log line.
    @Override
    public String toString() {
        return "MosipProperties[enabled=" + enabled + ", baseUrl=" + baseUrl + ", clientId=" + clientId
                + ", redirectUri=" + redirectUri + ", keyId=" + keyId + ", privateKeyPath="
                + (isBlank(privateKeyPath) ? "(empty)" : "****") + ", scope=" + scope + ", acrValues=" + acrValues
                + "]";
    }
}
