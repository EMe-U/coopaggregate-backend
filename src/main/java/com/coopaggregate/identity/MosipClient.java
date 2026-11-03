package com.coopaggregate.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestOperations;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Talks to eSignet: the authorize URL, the token call (authenticated with a signed client assertion and PKCE) and the
 * userinfo call. The ID token and the userinfo answer come back signed only (PS256) and are checked against the
 * provider's published keys (JWKS). Codes, tokens, assertions and claims are never logged, and error messages never
 * carry the provider's answer.
 */
public class MosipClient {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    private static final Logger log = LoggerFactory.getLogger(MosipClient.class);
    private static final String UNTRUSTED = "The identity provider's answer could not be verified.";
    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    // The OIDC claims request: only the name and the phone, both essential, so eSignet shows them as required on its
    // consent screen. A member who does not allow them can only cancel.
    static final String CLAIMS_REQUEST =
            "{\"userinfo\":{\"name\":{\"essential\":true},\"phone_number\":{\"essential\":true}}}";

    private final MosipProperties properties;
    private final MosipSigningKey signingKey;
    private final RestClient restClient;
    private final NimbusJwtDecoder decoder;
    private final Clock clock;

    /** jwksOperations fetches the provider's keys; both it and restClient should have timeouts. */
    public MosipClient(MosipProperties properties, MosipSigningKey signingKey, RestClient restClient,
                       RestOperations jwksOperations, Clock clock) {
        this.properties = properties;
        this.signingKey = signingKey;
        this.restClient = restClient;
        this.clock = clock;
        log.info("Identity verification is on. Token: {}, userinfo: {}, JWKS: {}", Diagnostics.safeUrl(properties.tokenUrl()),
                Diagnostics.safeUrl(properties.userinfoUrl()), Diagnostics.safeUrl(properties.jwksUrl()));
        this.decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUrl())
                .jwsAlgorithm(SignatureAlgorithm.PS256)
                .restOperations(jwksOperations)
                .build();
        // The algorithm is fixed to PS256 above, so "none" and HMAC tokens are refused before these checks.
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(properties.issuer()),
                audienceIsOurClient(),
                hasExpiryAndSubject()));
    }

    /**
     * The URL the member is sent to. The challenge is the S256 hash of the verifier kept for the callback; the claims
     * parameter asks for the name and phone only.
     */
    public String authorizationUrl(String state, String nonce, String codeChallenge) {
        return UriComponentsBuilder.fromUriString(properties.authorizationUrl())
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("scope", properties.scope())
                .queryParam("state", state)
                .queryParam("nonce", nonce)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("acr_values", properties.acrValues())
                .queryParam("claims", "{claims}")
                .encode().buildAndExpand(Map.of("claims", CLAIMS_REQUEST)).toUriString();
    }

    /**
     * Exchanges the code for the person's claims: token call, ID token check (signature, issuer, audience, expiry and
     * nonce), userinfo call and check, and the same subject in both.
     */
    public MosipClaims fetchClaims(String code, String codeVerifier, String nonce) {
        Map<String, Object> tokens = exchangeCode(code, codeVerifier);
        String idToken = text(tokens.get("id_token"));
        String accessToken = text(tokens.get("access_token"));
        if (idToken == null || accessToken == null) {
            throw untrusted();
        }
        Jwt id = verify(idToken, "id_token");
        if (nonce == null || !nonce.equals(id.getClaimAsString("nonce"))) {
            throw untrusted();
        }
        Jwt userinfo = verify(userinfo(accessToken), "userinfo");
        if (!id.getSubject().equals(userinfo.getSubject())) {
            throw untrusted();
        }
        return new MosipClaims(userinfo.getSubject(), claim(userinfo, "name"), claim(userinfo, "phone_number"));
    }

    private Map<String, Object> exchangeCode(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());
        form.add("client_id", properties.clientId());
        form.add("code_verifier", codeVerifier);
        form.add("client_assertion_type", ASSERTION_TYPE);
        form.add("client_assertion", MosipClientAssertion.create(properties, signingKey, clock));
        try {
            Map<String, Object> body = restClient.post()
                    .uri(properties.tokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { });
            if (body == null) {
                throw untrusted();
            }
            return body;
        } catch (RestClientException e) {
            throw failure(e, "token", "POST", properties.tokenUrl());
        }
    }

    // The answer is a signed JWT (application/jwt), not JSON.
    private String userinfo(String accessToken) {
        try {
            String body = restClient.get()
                    .uri(properties.userinfoUrl())
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .accept(MediaType.parseMediaType("application/jwt"), MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank() || body.trim().startsWith("{")) {
                throw untrusted();
            }
            return body.trim();
        } catch (RestClientException e) {
            throw failure(e, "userinfo", "GET", properties.userinfoUrl());
        }
    }

    private Jwt verify(String token, String what) {
        try {
            return decoder.decode(token);
        } catch (JwtException e) {
            throw IdentityProviderException.failed(UNTRUSTED, Diagnostics.token(what, properties.jwksUrl(), e));
        }
    }

    // Not logged and not shown: the answer of the provider can hold the code or personal data.
    // The message goes to the client and stays the same; the detail is only for the log.
    private static IdentityProviderException failure(RestClientException e, String call, String method, String url) {
        String detail = Diagnostics.call(call + " request", method, url, e);
        if (e instanceof RestClientResponseException) {
            return IdentityProviderException.failed("The identity provider refused the " + call + " request.", detail);
        }
        if (e instanceof ResourceAccessException) {
            return IdentityProviderException.failed("The identity provider could not be reached.", detail);
        }
        return IdentityProviderException.failed("The identity provider's answer could not be read.", detail);
    }

    private static IdentityProviderException untrusted() {
        return IdentityProviderException.failed(UNTRUSTED);
    }

    private OAuth2TokenValidator<Jwt> audienceIsOurClient() {
        return jwt -> jwt.getAudience() != null && jwt.getAudience().contains(properties.clientId())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }

    private static OAuth2TokenValidator<Jwt> hasExpiryAndSubject() {
        return jwt -> jwt.getExpiresAt() != null && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
    }

    private static String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    // eSignet picks the language and sends a plain claim ("name"). A claim tagged with a language ("name#eng") is
    // only used when the plain one is missing; with several, the first language code in alphabetical order wins.
    private static String claim(Jwt jwt, String name) {
        String plain = text(jwt.getClaims().get(name));
        if (plain != null) {
            return plain;
        }
        return jwt.getClaims().entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(name + "#"))
                .map(entry -> text(entry.getValue()))
                .filter(java.util.Objects::nonNull)
                .sorted()
                .findFirst()
                .orElse(null);
    }
}
