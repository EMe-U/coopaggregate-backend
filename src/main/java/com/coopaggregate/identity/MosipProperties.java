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
        String jwksUri,
        String expectedIssuer,
        String expectedUserinfoIssuer,
        String allowedAcrs,
        String optionalClaims,
        String nationalIdClaim,
        boolean promptConsent) {

    // Only openid: the details are asked for with the claims parameter (see MosipClient), so no scope brings in
    // details the cooperative does not use ("profile" would also release the picture).
    static final String DEFAULT_SCOPE = "openid";
    private static final List<String> NEEDED_SCOPES = List.of("openid", "phone");
    static final String DEFAULT_ACR = "mosip:idp:acr:password";
    // Only the one-time code: the local eSignet mock accepts any biometric capture, so biometrics prove nothing.
    static final String DEFAULT_ALLOWED_ACRS = "mosip:idp:acr:generated-code";
    // The optional details the member may allow or refuse on eSignet's consent page. These four are the only ones read.
    static final List<String> KNOWN_OPTIONAL_CLAIMS = List.of("birthdate", "gender", "email", "address");
    static final String DEFAULT_OPTIONAL_CLAIMS = String.join(" ", KNOWN_OPTIONAL_CLAIMS);
    // Never asked for, whatever the settings say.
    private static final List<String> NEVER_REQUESTED = List.of("picture", "name", "phone_number");
    private static final java.util.regex.Pattern CLAIM_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    public MosipProperties {
        baseUrl = isBlank(baseUrl) ? null : baseUrl.trim().replaceAll("/+$", "");
        scope = isBlank(scope) ? DEFAULT_SCOPE : scope.trim();
        acrValues = isBlank(acrValues) ? DEFAULT_ACR : acrValues.trim();
        allowedAcrs = isBlank(allowedAcrs) ? DEFAULT_ALLOWED_ACRS : allowedAcrs.trim();
        optionalClaims = isBlank(optionalClaims) ? DEFAULT_OPTIONAL_CLAIMS : optionalClaims.trim();
        nationalIdClaim = isBlank(nationalIdClaim) ? null : nationalIdClaim.trim();
    }

    /**
     * The optional claims asked for, in the order of MOSIP_OPTIONAL_CLAIMS (space-separated). Only birthdate, gender,
     * email and address can be asked for; any other name (picture included) is left out. Empty means all four.
     */
    public List<String> requestedOptionalClaims() {
        return java.util.Arrays.stream(optionalClaims.split("\\s+"))
                .filter(KNOWN_OPTIONAL_CLAIMS::contains)
                .distinct()
                .toList();
    }

    /** Names in MOSIP_OPTIONAL_CLAIMS that are not asked for because they are not one of the four. Names only. */
    public List<String> ignoredOptionalClaims() {
        return java.util.Arrays.stream(optionalClaims.split("\\s+"))
                .filter(name -> !name.isEmpty() && !KNOWN_OPTIONAL_CLAIMS.contains(name))
                .distinct()
                .toList();
    }

    /**
     * The claim MOSIP_NATIONAL_ID_CLAIM names, asked for as optional, or null when it is empty (the default) or not a
     * plain claim name. The national ID itself stays entered by the manager.
     */
    public String requestedNationalIdClaim() {
        return nationalIdClaim != null && CLAIM_NAME.matcher(nationalIdClaim).matches()
                && !NEVER_REQUESTED.contains(nationalIdClaim) && !KNOWN_OPTIONAL_CLAIMS.contains(nationalIdClaim)
                ? nationalIdClaim : null;
    }

    /**
     * Whether an id_token "acr" (how the person logged in) is one of MOSIP_ALLOWED_ACRS (space-separated, compared
     * exactly).
     */
    public boolean isAllowedAcr(String acr) {
        return acr != null && java.util.Arrays.asList(allowedAcrs.split("\\s+")).contains(acr);
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

    /** The iss the ID token must carry, compared exactly: MOSIP_ISSUER when it is set, otherwise MOSIP_BASE_URL. */
    public String issuer() {
        return isBlank(expectedIssuer) ? baseUrl : expectedIssuer.trim();
    }

    /**
     * The iss the userinfo answer must carry, compared exactly: MOSIP_USERINFO_ISSUER when it is set, otherwise the ID
     * token's (some eSignet setups sign the two with different issuers).
     */
    public String userinfoIssuer() {
        return isBlank(expectedUserinfoIssuer) ? issuer() : expectedUserinfoIssuer.trim();
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
                + ", allowedAcrs=" + allowedAcrs + ", optionalClaims=" + requestedOptionalClaims()
                + ", nationalIdClaim=" + nationalIdClaim + ", promptConsent=" + promptConsent + "]";
    }
}
