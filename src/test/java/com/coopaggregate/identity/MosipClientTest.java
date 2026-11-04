package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.text.ParseException;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

class MosipClientTest {

    private static final String BASE = "http://localhost:8080";
    private static final String NONCE = "the-nonce";
    private static final String CODE = "secret-authorization-code";
    private static final String VERIFIER = "secret-code-verifier-0123456789-abcdefghijklmnop";

    private static RSAKey providerKey;
    private static RSAKey strangerKey;
    private static RSAKey unknownKey;
    private static KeyPair cooperativeKey;

    private MockRestServiceServer http;
    private MockRestServiceServer jwks;
    private MosipClient client;

    @BeforeAll
    static void createKeys() throws Exception {
        // Like eSignet's JWKS: the key's metadata says PS256, though the userinfo answer is signed with it using RS256.
        providerKey = new RSAKeyGenerator(2048).keyID("provider-1").algorithm(JWSAlgorithm.PS256)
                .keyUse(com.nimbusds.jose.jwk.KeyUse.SIGNATURE).generate();
        strangerKey = new RSAKeyGenerator(2048).keyID("provider-1").generate();
        unknownKey = new RSAKeyGenerator(2048).keyID("unknown-kid").generate();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        cooperativeKey = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        useIssuers(null, null);
    }

    private void useIssuer(String issuer) {
        useIssuers(issuer, null);
    }

    // A client whose MOSIP_ISSUER and MOSIP_USERINFO_ISSUER are the given values (null: not set).
    private void useIssuers(String issuer, String userinfoIssuer) {
        RestClient.Builder builder = RestClient.builder();
        http = MockRestServiceServer.bindTo(builder).build();
        RestTemplate jwksTemplate = new RestTemplate();
        jwks = MockRestServiceServer.bindTo(jwksTemplate).build();
        jwks.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/oauth2/jwks")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(new JWKSet(providerKey.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
        MosipProperties properties = new MosipProperties(true, BASE, "coopaggregate",
                "http://localhost:5173/identity/callback", "unused", "coopaggregate-key", null, null, null, null, null,
                null, null, issuer, userinfoIssuer);
        client = new MosipClient(properties,
                new MosipSigningKey((RSAPrivateKey) cooperativeKey.getPrivate(), "coopaggregate-key"), builder.build(),
                jwksTemplate, Clock.systemUTC());
    }

    // --- Authorize URL

    @Test
    void theAuthorizeUrlHasEveryParameterEsignetNeeds() {
        String url = client.authorizationUrl("the-state", NONCE, "the-challenge");

        assertTrue(url.startsWith(BASE + "/oauth2/authorize?"), url);
        Map<String, String> query = query(url.substring(url.indexOf('?') + 1));
        assertEquals("code", query.get("response_type"));
        assertEquals("coopaggregate", query.get("client_id"));
        assertEquals("http://localhost:5173/identity/callback", query.get("redirect_uri"));
        assertEquals("openid", query.get("scope"));
        assertEquals("the-state", query.get("state"));
        assertEquals(NONCE, query.get("nonce"));
        assertEquals("the-challenge", query.get("code_challenge"));
        assertEquals("S256", query.get("code_challenge_method"));
        assertEquals("mosip:idp:acr:password", query.get("acr_values"));
    }

    @Test
    void theAuthorizeUrlAsksForTheNameAndPhoneOnlyBothEssential() throws Exception {
        String url = client.authorizationUrl("the-state", NONCE, "the-challenge");

        String claims = query(url.substring(url.indexOf('?') + 1)).get("claims");
        com.fasterxml.jackson.databind.JsonNode request = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(claims);
        assertEquals(1, request.size(), "userinfo claims only");
        com.fasterxml.jackson.databind.JsonNode userinfo = request.get("userinfo");
        assertEquals(List.of("name", "phone_number"), iterable(userinfo.fieldNames()));
        assertTrue(userinfo.get("name").get("essential").asBoolean());
        assertTrue(userinfo.get("phone_number").get("essential").asBoolean());
        assertFalse(url.contains("{") || url.contains("}") || url.contains("\""), "the JSON is URL-encoded: " + url);
    }

    private static List<String> iterable(java.util.Iterator<String> names) {
        List<String> list = new java.util.ArrayList<>();
        names.forEachRemaining(list::add);
        return list;
    }

    // --- The whole exchange

    @Test
    void theClaimsComeBackFromTheSignedUserinfoAfterEveryCheck() throws Exception {
        provider(idToken(idClaims()), userinfo(userinfoClaims()));

        MosipClaims claims = client.fetchClaims(CODE, VERIFIER, NONCE);

        assertEquals("pairwise-sub", claims.subject());
        assertEquals("Uwimana Claudine", claims.name());
        assertEquals("+250788123456", claims.phoneNumber());
        assertFalse(claims.toString().contains("Uwimana"));
        http.verify();
    }

    @Test
    void theTokenCallCarriesThePkceVerifierAndASignedAssertionAndNothingElseSecret() throws Exception {
        http.expect(requestTo(BASE + "/oauth2/token")).andExpect(method(HttpMethod.POST))
                .andExpect(request -> {
                    try {
                        checkTokenRequest((MockClientHttpRequest) request);
                    } catch (ParseException | JOSEException e) {
                        throw new AssertionError(e);
                    }
                })
                .andRespond(withSuccess(tokenBody(idToken(idClaims())), MediaType.APPLICATION_JSON));
        http.expect(requestTo(BASE + "/oauth2/userinfo")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer secret-access-token"))
                .andRespond(withSuccess(userinfo(userinfoClaims()), MediaType.valueOf("application/jwt")));

        client.fetchClaims(CODE, VERIFIER, NONCE);

        http.verify();
    }

    private static void checkTokenRequest(MockClientHttpRequest request) throws ParseException, JOSEException {
        assertTrue(request.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED));
        Map<String, String> form = query(request.getBodyAsString());
        assertEquals("authorization_code", form.get("grant_type"));
        assertEquals(CODE, form.get("code"));
        assertEquals(VERIFIER, form.get("code_verifier"));
        assertEquals("http://localhost:5173/identity/callback", form.get("redirect_uri"));
        assertEquals("coopaggregate", form.get("client_id"));
        assertEquals("urn:ietf:params:oauth:client-assertion-type:jwt-bearer", form.get("client_assertion_type"));
        SignedJWT assertion = SignedJWT.parse(form.get("client_assertion"));
        assertEquals(JWSAlgorithm.PS256, assertion.getHeader().getAlgorithm());
        assertEquals("coopaggregate-key", assertion.getHeader().getKeyID());
        assertTrue(assertion.verify(new RSASSAVerifier((RSAPublicKey) cooperativeKey.getPublic())));
        assertEquals(BASE, assertion.getJWTClaimsSet().getAudience().get(0));
        assertFalse(form.containsKey("client_secret"));
    }

    @Test
    void aLanguageTaggedClaimIsUsedWhenThePlainOneIsMissing() throws Exception {
        JWTClaimsSet.Builder claims = userinfoClaims();
        claims.claim("name", null);
        claims.claim("name#eng", "Uwimana Claudine");
        provider(idToken(idClaims()), userinfo(claims));

        assertEquals("Uwimana Claudine", client.fetchClaims(CODE, VERIFIER, NONCE).name());
    }

    @Test
    void thePlainNameWinsOverLanguageTaggedOnes() throws Exception {
        JWTClaimsSet.Builder claims = userinfoClaims();
        claims.claim("name#kin", "Uwimana Klodina");
        provider(idToken(idClaims()), userinfo(claims));

        assertEquals("Uwimana Claudine", client.fetchClaims(CODE, VERIFIER, NONCE).name());
    }

    @Test
    void withSeveralLanguagesAndNoPlainNameTheFirstLanguageCodeWins() throws Exception {
        JWTClaimsSet.Builder claims = userinfoClaims();
        claims.claim("name", null);
        claims.claim("name#kin", "Uwimana Klodina");
        claims.claim("name#eng", "Uwimana Claudine");
        provider(idToken(idClaims()), userinfo(claims));

        assertEquals("Uwimana Claudine", client.fetchClaims(CODE, VERIFIER, NONCE).name());
    }

    @Test
    void aBirthDateInAnyFormatIsIgnored() throws Exception {
        JWTClaimsSet.Builder claims = userinfoClaims();
        claims.claim("birthdate", "not/a/date");
        provider(idToken(idClaims()), userinfo(claims));

        MosipClaims result = client.fetchClaims(CODE, VERIFIER, NONCE);

        assertEquals("Uwimana Claudine", result.name());
        assertEquals(List.of("name", "phoneNumber", "subject"), java.util.Arrays.stream(
                MosipClaims.class.getRecordComponents()).map(c -> c.getName()).sorted().toList());
    }

    @Test
    void claimsThatAreMissingAreNull() throws Exception {
        JWTClaimsSet.Builder claims = userinfoClaims();
        claims.claim("name", null);
        claims.claim("phone_number", null);
        provider(idToken(idClaims()), userinfo(claims));

        MosipClaims result = client.fetchClaims(CODE, VERIFIER, NONCE);

        assertNull(result.name());
        assertNull(result.phoneNumber());
    }

    // --- The ID token is refused when anything is wrong with it

    @Test
    void anIdTokenSignedByAnotherKeyIsRefused() throws Exception {
        assertRefused(sign(strangerKey, idClaims()), userinfo(userinfoClaims()));
    }

    @Test
    void anIdTokenOfAnotherIssuerIsRefused() throws Exception {
        assertRefused(idToken(idClaims().issuer("http://evil.example")), userinfo(userinfoClaims()));
    }

    @Test
    void anIdTokenForAnotherClientIsRefused() throws Exception {
        assertRefused(idToken(idClaims().audience("another-client")), userinfo(userinfoClaims()));
    }

    @Test
    void anExpiredIdTokenIsRefused() throws Exception {
        assertRefused(idToken(idClaims().expirationTime(new Date(System.currentTimeMillis() - 600_000))),
                userinfo(userinfoClaims()));
    }

    @Test
    void anIdTokenWithoutAnExpiryIsRefused() throws Exception {
        assertRefused(idToken(idClaims().expirationTime(null)), userinfo(userinfoClaims()));
    }

    @Test
    void anIdTokenWithAnotherNonceIsRefused() throws Exception {
        assertRefused(idToken(idClaims().claim("nonce", "someone-elses-nonce")), userinfo(userinfoClaims()));
    }

    @Test
    void anIdTokenWithoutANonceIsRefused() throws Exception {
        assertRefused(idToken(idClaims().claim("nonce", null)), userinfo(userinfoClaims()));
    }

    @Test
    void anIdTokenWithoutASubjectIsRefused() throws Exception {
        assertRefused(idToken(idClaims().subject(null)), userinfo(userinfoClaims()));
    }

    @Test
    void anUnsignedIdTokenIsRefused() throws Exception {
        String plain = new PlainJWT(new PlainHeader(), idClaims().build()).serialize();

        assertRefused(plain, userinfo(userinfoClaims()));
    }

    @Test
    void anIdTokenSignedWithAHmacSecretIsRefused() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("provider-1").build(),
                idClaims().build());
        jwt.sign(new MACSigner("0123456789abcdef0123456789abcdef"));

        assertRefused(jwt.serialize(), userinfo(userinfoClaims()));
    }

    @Test
    void anIdTokenThatIsNotATokenIsRefused() {
        assertRefused("not-a-token", "also-not-a-token");
    }

    // --- The userinfo answer is refused when anything is wrong with it

    @Test
    void aUserinfoSignedByAnotherKeyIsRefused() throws Exception {
        assertRefused(idToken(idClaims()), sign(strangerKey, userinfoClaims()));
    }

    @Test
    void aUserinfoForAnotherPersonIsRefused() throws Exception {
        assertRefused(idToken(idClaims()), userinfo(userinfoClaims().subject("another-person")));
    }

    @Test
    void aUserinfoForAnotherClientIsRefused() throws Exception {
        assertRefused(idToken(idClaims()), userinfo(userinfoClaims().audience("another-client")));
    }

    @Test
    void aUserinfoOfAnotherIssuerIsRefused() throws Exception {
        assertRefused(idToken(idClaims()), userinfo(userinfoClaims().issuer("http://evil.example")));
    }

    @Test
    void anExpiredUserinfoIsRefused() throws Exception {
        assertRefused(idToken(idClaims()),
                userinfo(userinfoClaims().expirationTime(new Date(System.currentTimeMillis() - 600_000))));
    }

    @Test
    void aUserinfoWithoutAnIssuerIsRefused() throws Exception {
        assertRefused(idToken(idClaims()), userinfo(userinfoClaims().issuer(null)));
    }

    @Test
    void aUserinfoWithoutAnAudienceIsRefused() throws Exception {
        assertRefused(idToken(idClaims()), userinfo(userinfoClaims().audience((String) null)));
    }

    @Test
    void anIdTokenWithoutAnIssuerIsRefused() throws Exception {
        assertRefused(idToken(idClaims().issuer(null)), userinfo(userinfoClaims()));
    }

    // --- The userinfo algorithm: exactly PS256 or RS256, with the JWKS key its kid names

    @Test
    void anRs256UserinfoSignedByTheJwksKeyIsAcceptedThoughTheJwksSaysPs256() throws Exception {
        provider(idToken(idClaims()), sign(providerKey, JWSAlgorithm.RS256, userinfoClaims()));

        MosipClaims claims = client.fetchClaims(CODE, VERIFIER, NONCE);

        assertEquals("pairwise-sub", claims.subject());
        assertEquals("Uwimana Claudine", claims.name());
        assertEquals("+250788123456", claims.phoneNumber());
    }

    @Test
    void anRs256UserinfoSignedByAnUnknownKeyIsRefused() throws Exception {
        assertRefused(idToken(idClaims()), sign(unknownKey, JWSAlgorithm.RS256, userinfoClaims()));
    }

    @Test
    void anRs256UserinfoWithTheRightKidButAnotherKeyIsRefused() throws Exception {
        assertRefused(idToken(idClaims()), sign(strangerKey, JWSAlgorithm.RS256, userinfoClaims()));
    }

    @Test
    void anRs256UserinfoWithoutAKidIsRefused() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).build(), userinfoClaims().build());
        jwt.sign(new RSASSASigner(providerKey));

        assertRefused(idToken(idClaims()), jwt.serialize());
    }

    @Test
    void anUnsignedUserinfoIsRefused() throws Exception {
        assertRefused(idToken(idClaims()), new PlainJWT(new PlainHeader(), userinfoClaims().build()).serialize());
    }

    @Test
    void anHs256UserinfoIsRefused() throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("provider-1").build(),
                userinfoClaims().build());
        jwt.sign(new MACSigner("0123456789abcdef0123456789abcdef"));

        assertRefused(idToken(idClaims()), jwt.serialize());
    }

    @Test
    void anEs256UserinfoIsRefused() throws Exception {
        com.nimbusds.jose.jwk.ECKey ec = new com.nimbusds.jose.jwk.gen.ECKeyGenerator(com.nimbusds.jose.jwk.Curve.P_256)
                .keyID("provider-1").generate();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID("provider-1").build(),
                userinfoClaims().build());
        jwt.sign(new com.nimbusds.jose.crypto.ECDSASigner(ec));

        assertRefused(idToken(idClaims()), jwt.serialize());
    }

    @Test
    void anRs384UserinfoIsRefused() throws Exception {
        assertRefused(idToken(idClaims()), sign(providerKey, JWSAlgorithm.RS384, userinfoClaims()));
    }

    @Test
    void anRs256IdTokenIsStillRefused() throws Exception {
        assertRefused(sign(providerKey, JWSAlgorithm.RS256, idClaims()), userinfo(userinfoClaims()));
    }

    // --- The userinfo expiry: optional, but refused once passed

    @Test
    void aUserinfoWithoutExpOrIatIsAccepted() throws Exception {
        provider(idToken(idClaims()),
                sign(providerKey, JWSAlgorithm.RS256, userinfoClaims().expirationTime(null).issueTime(null)));

        assertEquals("Uwimana Claudine", client.fetchClaims(CODE, VERIFIER, NONCE).name());
    }

    @Test
    void anExpiredRs256UserinfoIsRefused() throws Exception {
        assertRefused(idToken(idClaims()), sign(providerKey, JWSAlgorithm.RS256,
                userinfoClaims().issueTime(null).expirationTime(new Date(System.currentTimeMillis() - 600_000))));
    }

    @Test
    void anRs256UserinfoForAnotherPersonIsRefused() throws Exception {
        assertRefused(idToken(idClaims()),
                sign(providerKey, JWSAlgorithm.RS256, userinfoClaims().subject("another-person")));
    }

    // --- MOSIP_ISSUER

    private static final String ESIGNET_ISSUER = "http://localhost:8088/v1/esignet";

    @Test
    void withoutMosipIssuerTheBaseUrlIsTheIssuer() {
        assertEquals(BASE, new MosipProperties(true, BASE + "/", "c", "r", "k", "kid", null, null, null, null, null,
                null, null, " ", null).issuer());
    }

    @Test
    void mosipIssuerIsTheIssuerBothTokensMustCarry() throws Exception {
        useIssuer(ESIGNET_ISSUER);
        provider(idToken(idClaims().issuer(ESIGNET_ISSUER)),
                sign(providerKey, JWSAlgorithm.RS256, userinfoClaims().issuer(ESIGNET_ISSUER)));

        assertEquals("Uwimana Claudine", client.fetchClaims(CODE, VERIFIER, NONCE).name());
    }

    @Test
    void withMosipIssuerAUserinfoWithTheBaseUrlIsRefused() throws Exception {
        useIssuer(ESIGNET_ISSUER);

        assertRefused(idToken(idClaims().issuer(ESIGNET_ISSUER)), userinfo(userinfoClaims()));
    }

    @Test
    void withMosipIssuerAnIdTokenWithTheBaseUrlIsRefused() throws Exception {
        useIssuer(ESIGNET_ISSUER);

        assertRefused(idToken(idClaims()), userinfo(userinfoClaims().issuer(ESIGNET_ISSUER)));
    }

    @Test
    void withMosipIssuerTheComparisonIsExact() throws Exception {
        useIssuer(ESIGNET_ISSUER);

        assertRefused(idToken(idClaims().issuer(ESIGNET_ISSUER)),
                userinfo(userinfoClaims().issuer(ESIGNET_ISSUER + "/")));
    }

    @Test
    void withMosipIssuerAUserinfoWithoutAnIssuerIsRefused() throws Exception {
        useIssuer(ESIGNET_ISSUER);

        assertRefused(idToken(idClaims().issuer(ESIGNET_ISSUER)), userinfo(userinfoClaims().issuer(null)));
    }

    // --- MOSIP_USERINFO_ISSUER: this eSignet signs the id_token as BASE and the userinfo answer as ESIGNET_ISSUER

    private String esignetUserinfo(String iss) throws Exception {
        return sign(providerKey, JWSAlgorithm.RS256, userinfoClaims().issuer(iss).expirationTime(null).issueTime(null));
    }

    @Test
    void theUserinfoIssuerDefaultsToTheIdTokenIssuer() {
        assertEquals(BASE, new MosipProperties(true, BASE + "/", "c", "r", "k", "kid", null, null, null, null, null,
                null, null, null, " ").userinfoIssuer());
        assertEquals(ESIGNET_ISSUER, new MosipProperties(true, BASE, "c", "r", "k", "kid", null, null, null, null, null,
                null, null, ESIGNET_ISSUER, null).userinfoIssuer());
    }

    @Test
    void aUserinfoOfTheEsignetIssuerIsAcceptedWhenMosipUserinfoIssuerSaysSo() throws Exception {
        useIssuers(null, ESIGNET_ISSUER);
        provider(idToken(idClaims()), esignetUserinfo(ESIGNET_ISSUER));

        MosipClaims claims = client.fetchClaims(CODE, VERIFIER, NONCE);

        assertEquals("Uwimana Claudine", claims.name());
        assertEquals("+250788123456", claims.phoneNumber());
    }

    @Test
    void aUserinfoOfTheEsignetIssuerIsRefusedWithoutMosipUserinfoIssuer() throws Exception {
        assertRefused(idToken(idClaims()), esignetUserinfo(ESIGNET_ISSUER));
    }

    @Test
    void anIdTokenOfTheEsignetIssuerIsStillRefusedWithoutMosipIssuer() throws Exception {
        useIssuers(null, ESIGNET_ISSUER);

        assertRefused(idToken(idClaims().issuer(ESIGNET_ISSUER)), esignetUserinfo(ESIGNET_ISSUER));
    }

    @Test
    void withMosipUserinfoIssuerAUserinfoOfTheBaseUrlIsRefused() throws Exception {
        useIssuers(null, ESIGNET_ISSUER);

        assertRefused(idToken(idClaims()), esignetUserinfo(BASE));
    }

    @Test
    void withMosipUserinfoIssuerAUserinfoWithoutAnIssuerIsRefused() throws Exception {
        useIssuers(null, ESIGNET_ISSUER);

        assertRefused(idToken(idClaims()), esignetUserinfo(null));
    }

    @Test
    void withMosipUserinfoIssuerTheComparisonIsExact() throws Exception {
        useIssuers(null, ESIGNET_ISSUER);
        assertRefused(idToken(idClaims()), esignetUserinfo(ESIGNET_ISSUER + "/"));

        useIssuers(null, ESIGNET_ISSUER);
        assertRefused(idToken(idClaims()), esignetUserinfo("http://localhost:8088/v1"));

        useIssuers(null, ESIGNET_ISSUER);
        assertRefused(idToken(idClaims()), esignetUserinfo(ESIGNET_ISSUER + "/extra"));

        // A configured value keeps its trailing slash: no trimming of "/" here, only of spaces.
        useIssuers(null, " " + ESIGNET_ISSUER + "/ ");
        assertRefused(idToken(idClaims()), esignetUserinfo(ESIGNET_ISSUER));
    }

    @Test
    void theDefaultUserinfoIssuerIsTheBaseUrlWithoutItsTrailingSlash() throws Exception {
        assertRefused(idToken(idClaims()), esignetUserinfo(BASE + "/"));
    }

    @Test
    void mosipUserinfoIssuerDoesNotChangeTheIdTokenIssuer() throws Exception {
        useIssuers(ESIGNET_ISSUER, BASE);
        provider(idToken(idClaims().issuer(ESIGNET_ISSUER)), esignetUserinfo(BASE));

        assertEquals("Uwimana Claudine", client.fetchClaims(CODE, VERIFIER, NONCE).name());
    }

    @Test
    void aUserinfoThatIsPlainJsonIsRefusedBecauseItIsNotSigned() throws Exception {
        assertRefused(idToken(idClaims()), "{\"sub\":\"pairwise-sub\",\"name\":\"Uwimana Claudine\"}");
    }

    // --- Provider failures

    @Test
    void aTokenEndpointThatRefusesGivesAProviderErrorWithoutTheAnswer() {
        http.expect(requestTo(BASE + "/oauth2/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"detail\":\"secret-detail " + CODE + "\"}"));

        IdentityProviderException e = assertThrows(IdentityProviderException.class,
                () -> client.fetchClaims(CODE, VERIFIER, NONCE));

        assertFalse(e.isNotConfigured());
        assertEquals("The identity provider refused the token request.", e.getMessage());
        assertNoSecrets(e);
    }

    @Test
    void aTokenEndpointThatFailsGivesAProviderError() {
        http.expect(requestTo(BASE + "/oauth2/token")).andRespond(withServerError());

        IdentityProviderException e = assertThrows(IdentityProviderException.class,
                () -> client.fetchClaims(CODE, VERIFIER, NONCE));

        assertEquals("The identity provider refused the token request.", e.getMessage());
    }

    @Test
    void aProviderThatCannotBeReachedGivesAProviderError() {
        http.expect(requestTo(BASE + "/oauth2/token")).andRespond(withException(new IOException("connection to "
                + BASE + " refused, code " + CODE)));

        IdentityProviderException e = assertThrows(IdentityProviderException.class,
                () -> client.fetchClaims(CODE, VERIFIER, NONCE));

        assertEquals("The identity provider could not be reached.", e.getMessage());
        assertNoSecrets(e);
    }

    @Test
    void aUserinfoEndpointThatRefusesGivesAProviderError() throws Exception {
        http.expect(requestTo(BASE + "/oauth2/token"))
                .andRespond(withSuccess(tokenBody(idToken(idClaims())), MediaType.APPLICATION_JSON));
        http.expect(requestTo(BASE + "/oauth2/userinfo")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        IdentityProviderException e = assertThrows(IdentityProviderException.class,
                () -> client.fetchClaims(CODE, VERIFIER, NONCE));

        assertEquals("The identity provider refused the userinfo request.", e.getMessage());
    }

    @Test
    void aTokenAnswerWithoutTokensIsRefused() {
        http.expect(requestTo(BASE + "/oauth2/token")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThrows(IdentityProviderException.class, () -> client.fetchClaims(CODE, VERIFIER, NONCE));
    }

    @Test
    void theKeysOfTheProviderThatCannotBeFetchedGiveAProviderError() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer httpServer = MockRestServiceServer.bindTo(builder).build();
        RestTemplate template = new RestTemplate();
        MockRestServiceServer keys = MockRestServiceServer.bindTo(template).build();
        keys.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/oauth2/jwks")).andRespond(withServerError());
        MosipClient broken = new MosipClient(new MosipProperties(true, BASE, "coopaggregate", "r", "k", "kid", null,
                null, null, null, null, null, null, null, null),
                new MosipSigningKey((RSAPrivateKey) cooperativeKey.getPrivate(), "kid"), builder.build(), template,
                Clock.systemUTC());
        httpServer.expect(requestTo(BASE + "/oauth2/token"))
                .andRespond(withSuccess(tokenBody(idToken(idClaims())), MediaType.APPLICATION_JSON));

        IdentityProviderException e = assertThrows(IdentityProviderException.class,
                () -> broken.fetchClaims(CODE, VERIFIER, NONCE));

        assertEquals("The identity provider's answer could not be verified.", e.getMessage());
    }

    @Test
    void theClaimsNeverPrint() {
        assertFalse(new MosipClaims("sub", "Uwimana Claudine", "+250788123456")
                .toString().contains("Uwimana"));
    }

    // --- Helpers

    private void assertRefused(String idToken, String userinfo) {
        provider(idToken, userinfo);

        IdentityProviderException e = assertThrows(IdentityProviderException.class,
                () -> client.fetchClaims(CODE, VERIFIER, NONCE));

        assertFalse(e.isNotConfigured());
        assertEquals("The identity provider's answer could not be verified.", e.getMessage());
        assertNoSecrets(e);
    }

    private static void assertNoSecrets(Throwable e) {
        assertFalse(e.getMessage().contains(CODE));
        assertFalse(e.getMessage().contains(VERIFIER));
        assertFalse(e.getMessage().contains("secret-access-token"));
        assertFalse(e.getMessage().contains("secret-detail"));
        assertNull(e.getCause(), "no cause that could carry the answer into a log");
    }

    private void provider(String idToken, String userinfo) {
        http.expect(requestTo(BASE + "/oauth2/token"))
                .andRespond(withSuccess(tokenBody(idToken), MediaType.APPLICATION_JSON));
        http.expect(requestTo(BASE + "/oauth2/userinfo"))
                .andRespond(withSuccess(userinfo, MediaType.valueOf("application/jwt")));
    }

    private static String tokenBody(String idToken) {
        return "{\"access_token\":\"secret-access-token\",\"token_type\":\"Bearer\",\"id_token\":\"" + idToken + "\"}";
    }

    private static JWTClaimsSet.Builder idClaims() {
        return new JWTClaimsSet.Builder()
                .issuer(BASE)
                .subject("pairwise-sub")
                .audience("coopaggregate")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .claim("nonce", NONCE)
                .jwtID(UUID.randomUUID().toString());
    }

    private static JWTClaimsSet.Builder userinfoClaims() {
        return new JWTClaimsSet.Builder()
                .issuer(BASE)
                .subject("pairwise-sub")
                .audience("coopaggregate")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .claim("name", "Uwimana Claudine")
                .claim("birthdate", "1990/05/17")
                .claim("phone_number", "+250788123456")
                .claim("email", "claudine@example.com");
    }

    private static String idToken(JWTClaimsSet.Builder claims) throws Exception {
        return sign(providerKey, claims);
    }

    private static String userinfo(JWTClaimsSet.Builder claims) throws Exception {
        return sign(providerKey, claims);
    }

    private static String sign(RSAKey key, JWTClaimsSet.Builder claims) throws Exception {
        return sign(key, JWSAlgorithm.PS256, claims);
    }

    private static String sign(RSAKey key, JWSAlgorithm algorithm, JWTClaimsSet.Builder claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(algorithm).keyID(key.getKeyID()).build(),
                claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static Map<String, String> query(String text) {
        Map<String, String> values = new HashMap<>();
        for (String pair : text.split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        assertNotNull(values);
        return values;
    }
}
