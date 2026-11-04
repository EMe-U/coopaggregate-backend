package com.coopaggregate.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;

/**
 * Talks to eSignet: the authorize URL, the token call (authenticated with a signed client assertion and PKCE) and the
 * userinfo call. The ID token (PS256) and the userinfo answer (PS256 or RS256) come back signed only and are checked
 * against the provider's published keys (JWKS). Codes, tokens, assertions and claims are never logged, and error messages never
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
    private static final Set<JWSAlgorithm> USERINFO_ALGORITHMS = Set.of(JWSAlgorithm.PS256, JWSAlgorithm.RS256);

    private final NimbusJwtDecoder decoder;
    private final NimbusJwtDecoder userinfoDecoder;
    private final Clock clock;
    private final TokenDiagnostics idTokenDiagnostics;
    private final TokenDiagnostics userinfoDiagnostics;

    /** jwksOperations fetches the provider's keys; both it and restClient should have timeouts. */
    public MosipClient(MosipProperties properties, MosipSigningKey signingKey, RestClient restClient,
                       RestOperations jwksOperations, Clock clock) {
        this.properties = properties;
        this.signingKey = signingKey;
        this.restClient = restClient;
        this.clock = clock;
        log.info("Identity verification is on. Token: {}, userinfo: {}, JWKS: {}", Diagnostics.safeUrl(properties.tokenUrl()),
                Diagnostics.safeUrl(properties.userinfoUrl()), Diagnostics.safeUrl(properties.jwksUrl()));
        log.info("Client assertion: {}", Diagnostics.signingKey(signingKey, properties.audience(),
                MosipClientAssertion.VALID_SECONDS));
        this.idTokenDiagnostics = new TokenDiagnostics(jwksOperations, properties.jwksUrl(), properties.issuer(),
                properties.clientId(), clock, CLOCK_SKEW, Set.of(JWSAlgorithm.PS256), true, false);
        this.userinfoDiagnostics = new TokenDiagnostics(jwksOperations, properties.jwksUrl(), properties.userinfoIssuer(),
                properties.clientId(), clock, CLOCK_SKEW, USERINFO_ALGORITHMS, false, true);
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
        // The userinfo answer: PS256 or RS256 only (eSignet signs it with RS256), verified with the JWKS key named by
        // its kid. "none", HMAC, EC and any other algorithm get no key and are refused. exp is optional (OIDC Core
        // 5.3.2 asks the signed userinfo for iss and aud only) but refused when it has passed; iss, aud and sub are
        // required as for the id_token. Its iss is MOSIP_USERINFO_ISSUER, which defaults to the id_token's.
        this.userinfoDecoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUrl())
                .jwsAlgorithms(algorithms -> {
                    algorithms.add(SignatureAlgorithm.PS256);
                    algorithms.add(SignatureAlgorithm.RS256);
                })
                .restOperations(jwksOperations)
                .jwtProcessorCustomizer(processor -> processor.setJWSKeySelector(new UserinfoKeySelector(
                        USERINFO_ALGORITHMS, ((JWSVerificationKeySelector<SecurityContext>) processor
                                .getJWSKeySelector()).getJWKSource())))
                .build();
        this.userinfoDecoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(properties.userinfoIssuer()),
                audienceIsOurClient(),
                hasSubject()));
    }

    /**
     * The URL the member is sent to. The challenge is the S256 hash of the verifier kept for the callback; the claims
     * parameter asks for the name and phone only.
     */
    public String authorizationUrl(String state, String nonce, String codeChallenge) {
        // Only the configured acr_values, as sent; nothing else from the URL (state, nonce, challenge) is logged.
        log.info("Identity verification authorize request acr_values: {}", properties.acrValues());
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
        Jwt id = verify(decoder, idTokenDiagnostics, idToken, "id_token", null, null);
        if (nonce == null || !nonce.equals(id.getClaimAsString("nonce"))) {
            throw untrusted();
        }
        UserinfoAnswer answer = userinfo(accessToken);
        Jwt userinfo = verify(userinfoDecoder, userinfoDiagnostics, answer.body(), "userinfo", answer.response(),
                id.getSubject());
        if (!id.getSubject().equals(userinfo.getSubject())) {
            throw IdentityProviderException.failed(UNTRUSTED, "userinfo check failed: "
                    + userinfoDiagnostics.describe(answer.body(), answer.response(), id.getSubject(), null));
        }
        return new MosipClaims(userinfo.getSubject(), claim(userinfo, "name"), claim(userinfo, "phone_number"),
                acr(id));
    }

    /** Whether an id_token acr is one of MOSIP_ALLOWED_ACRS. */
    public boolean isAllowedAcr(String acr) {
        return properties.isAllowedAcr(acr);
    }

    // The id_token's acr, or null when it has none. Only its presence is logged. A value that is not text is kept as
    // "" so it is present but never allowed.
    private static String acr(Jwt id) {
        Object acr = id.getClaims().get("acr");
        log.info("Identity verification id_token acr claim present: {}", acr != null);
        if (acr == null) {
            return null;
        }
        return acr instanceof String text ? text : "";
    }

    private Map<String, Object> exchangeCode(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", properties.redirectUri());
        form.add("client_id", properties.clientId());
        form.add("code_verifier", codeVerifier);
        form.add("client_assertion_type", ASSERTION_TYPE);
        String assertion = MosipClientAssertion.create(properties, signingKey, clock);
        form.add("client_assertion", assertion);
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
            throw failure(e, "token", "POST", properties.tokenUrl(), assertion);
        }
    }

    // The userinfo body, with its status and Content-Type for the log if it is refused.
    private record UserinfoAnswer(String body, TokenDiagnostics.Response response) {
    }

    // The answer is a signed JWT (application/jwt), not JSON.
    private UserinfoAnswer userinfo(String accessToken) {
        org.springframework.http.ResponseEntity<String> entity;
        try {
            entity = restClient.get()
                    .uri(properties.userinfoUrl())
                    .headers(headers -> headers.setBearerAuth(accessToken))
                    .accept(MediaType.parseMediaType("application/jwt"), MediaType.APPLICATION_JSON)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientException e) {
            throw failure(e, "userinfo", "GET", properties.userinfoUrl(), null);
        }
        String body = entity.getBody();
        MediaType type = entity.getHeaders().getContentType();
        TokenDiagnostics.Response response = new TokenDiagnostics.Response(entity.getStatusCode().value(),
                type == null ? null : type.toString());
        if (body == null || body.isBlank() || body.trim().startsWith("{")) {
            throw IdentityProviderException.failed(UNTRUSTED, "userinfo check failed: "
                    + userinfoDiagnostics.describe(body, response, null, null));
        }
        return new UserinfoAnswer(body.trim(), response);
    }

    // A refused token: the existing one-line reason, then (unless the provider's keys could not be fetched) what the
    // token looked like and which of our checks fail. See TokenDiagnostics for what is and is never shown.
    private Jwt verify(NimbusJwtDecoder tokenDecoder, TokenDiagnostics diagnostics, String token, String what,
                       TokenDiagnostics.Response response, String expectedSub) {
        try {
            return tokenDecoder.decode(token);
        } catch (JwtException e) {
            String detail = Diagnostics.token(what, properties.jwksUrl(), e);
            if (!detail.startsWith("JWKS request")) {
                detail = detail + "; " + diagnostics.describe(token, response, expectedSub,
                        e.getClass().getSimpleName());
            }
            throw IdentityProviderException.failed(UNTRUSTED, detail);
        }
    }

    // Not logged and not shown: the answer of the provider can hold the code or personal data.
    // The message goes to the client and stays the same; the detail is only for the log.
    // A 401 on the token call is about the client assertion, so the decoded assertion that was sent is added (never its
    // signature).
    private static IdentityProviderException failure(RestClientException e, String call, String method, String url,
                                                     String assertion) {
        String detail = Diagnostics.call(call + " request", method, url, e);
        if (assertion != null && e instanceof RestClientResponseException response
                && response.getStatusCode().value() == 401) {
            detail = detail + "; assertion sent: " + Diagnostics.assertion(assertion);
        }
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

    private static OAuth2TokenValidator<Jwt> hasSubject() {
        return jwt -> jwt.getSubject() != null && !jwt.getSubject().isBlank()
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
