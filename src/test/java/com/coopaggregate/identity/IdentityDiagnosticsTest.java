package com.coopaggregate.identity;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.MemberRepository;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * What is logged when the identity provider fails: where the call went and how it failed, and nothing that could be a
 * secret. The client still gets the same fixed message.
 */
@ExtendWith(OutputCaptureExtension.class)
class IdentityDiagnosticsTest {

    @Test
    void onlyKnownErrorCodesAreKept() {
        assertEquals("essential_consent_denied", Diagnostics.knownError("essential_consent_denied"));
        assertEquals("access_denied", Diagnostics.knownError(" Access_Denied "));
        assertNull(Diagnostics.knownError("consent rejected by Uwimana"));
        assertNull(Diagnostics.knownError(""));
        assertNull(Diagnostics.knownError(null));
    }

    private static final String BASE = "http://localhost:8080";
    private static final String CODE = "secret-authorization-code-xyz";
    private static final String BODY_SECRET = "secret-body-text";
    private static final String DESCRIPTION_SECRET = "secret-error-description";
    private static final String ACCESS_TOKEN = "secret-access-token";

    private static RSAKey providerKey;
    private static KeyPair cooperativeKey;

    private final Map<String, IdentityVerification> stored = new HashMap<>();
    private MockRestServiceServer http;
    private MockRestServiceServer jwks;
    private IdentityVerificationService service;
    private Manager manager;

    @BeforeAll
    static void createKeys() throws Exception {
        providerKey = new RSAKeyGenerator(2048).keyID("provider-1").generate();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        cooperativeKey = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        buildService(properties(null));
    }

    private void buildService(MosipProperties properties) {
        RestClient.Builder builder = RestClient.builder();
        http = MockRestServiceServer.bindTo(builder).build();
        RestTemplate jwksTemplate = new RestTemplate();
        jwks = MockRestServiceServer.bindTo(jwksTemplate).build();
        jwks.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/oauth2/jwks"))
                .andRespond(withSuccess(new JWKSet(providerKey.toPublicJWK()).toString(), MediaType.APPLICATION_JSON));
        MosipClient client = new MosipClient(properties,
                new MosipSigningKey((RSAPrivateKey) cooperativeKey.getPrivate(), "coopaggregate-key"), builder.build(),
                jwksTemplate, Clock.systemUTC());
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("client", client);

        IdentityVerificationRepository repository = mock(IdentityVerificationRepository.class);
        when(repository.save(any(IdentityVerification.class))).thenAnswer(invocation -> {
            IdentityVerification verification = invocation.getArgument(0);
            ReflectionTestUtils.setField(verification, "id", 9L);
            stored.put(verification.getStateHash(), verification);
            return verification;
        });
        when(repository.findForUpdateByStateHash(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.<String>getArgument(0))));
        manager = new Manager();
        ReflectionTestUtils.setField(manager, "id", 1L);
        service = new IdentityVerificationService(repository, mock(MemberRepository.class),
                mock(AuditLogRepository.class), beans.getBeanProvider(MosipClient.class));
    }

    // --- The token call

    @Test
    void aRefusedTokenCallLogsTheStatusAndTheStandardErrorCode(CapturedOutput output) {
        http.expect(requestTo(BASE + "/oauth2/token")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_client\",\"error_description\":\""
                        + DESCRIPTION_SECRET + "\",\"detail\":\"" + BODY_SECRET + "\"}"));

        IdentityProviderException e = fail();

        assertEquals("The identity provider refused the token request.", e.getMessage());
        assertTrue(output.getAll().contains("Identity verification 9 failed: The identity provider refused the token "
                + "request. [token request POST http://localhost:8080/oauth2/token: HTTP 400, error=invalid_client]"),
                output.getAll());
        assertNoSecrets(output);
    }

    @Test
    void aTokenCallThatFailsWithoutAJsonBodyLogsTheStatusOnly(CapturedOutput output) {
        http.expect(requestTo(BASE + "/oauth2/token")).andRespond(withServerError().contentType(MediaType.TEXT_HTML)
                .body("<html>" + BODY_SECRET + " " + CODE + "</html>"));

        IdentityProviderException e = fail();

        assertEquals("The identity provider refused the token request.", e.getMessage());
        assertTrue(output.getAll().contains("[token request POST http://localhost:8080/oauth2/token: HTTP 500]"),
                output.getAll());
        assertFalse(output.getAll().contains("error="));
        assertNoSecrets(output);
    }

    @Test
    void anErrorCodeThatIsNotAStandardOneIsLeftOut(CapturedOutput output) {
        http.expect(requestTo(BASE + "/oauth2/token")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"" + BODY_SECRET + "\"}"));

        fail();

        assertTrue(output.getAll().contains("HTTP 401; assertion sent: "), output.getAll());
        assertFalse(output.getAll().contains("error="));
        assertNoSecrets(output);
    }

    @Test
    void anErrorThatIsNotTextIsLeftOut(CapturedOutput output) {
        http.expect(requestTo(BASE + "/oauth2/token")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"x\":\"" + BODY_SECRET + "\"}}"));

        fail();

        assertTrue(output.getAll().contains("HTTP 400]"), output.getAll());
        assertNoSecrets(output);
    }

    @Test
    void aConnectionThatFailsLogsTheExceptionClassNotItsMessage(CapturedOutput output) {
        http.expect(requestTo(BASE + "/oauth2/token")).andRespond(withException(
                new ConnectException("Connection refused " + BODY_SECRET + " " + CODE)));

        IdentityProviderException e = fail();

        assertEquals("The identity provider could not be reached.", e.getMessage());
        assertTrue(output.getAll().contains("[token request POST http://localhost:8080/oauth2/token: "
                + "ConnectException]"), output.getAll());
        assertNoSecrets(output);
    }

    @Test
    void aTimeoutLogsTheExceptionClass(CapturedOutput output) {
        http.expect(requestTo(BASE + "/oauth2/token")).andRespond(withException(
                new SocketTimeoutException("Read timed out " + BODY_SECRET)));

        fail();

        assertTrue(output.getAll().contains("SocketTimeoutException]"), output.getAll());
        assertNoSecrets(output);
    }

    @Test
    void aTlsFailureLogsTheClassAndTheClassOfItsCause(CapturedOutput output) {
        SSLHandshakeException failure = new SSLHandshakeException("PKIX path building failed " + BODY_SECRET);
        failure.initCause(new java.security.cert.CertPathBuilderException("unable to find valid path " + BODY_SECRET));
        http.expect(requestTo(BASE + "/oauth2/token")).andRespond(withException(failure));

        fail();

        assertTrue(output.getAll().contains("SSLHandshakeException (cause: CertPathBuilderException)]"),
                output.getAll());
        assertNoSecrets(output);
    }

    @Test
    void theQueryStringOfTheAddressIsNeverLogged(CapturedOutput output) {
        buildService(properties("http://localhost:8080/oauth2/token?client_secret=secret-in-the-url#frag"));
        http.expect(requestTo(startsWith(BASE + "/oauth2/token"))).andRespond(withStatus(HttpStatus.BAD_REQUEST));

        fail();

        assertTrue(output.getAll().contains("POST http://localhost:8080/oauth2/token: HTTP 400]"), output.getAll());
        assertFalse(output.getAll().contains("secret-in-the-url"));
        assertFalse(output.getAll().contains("client_secret"));
        assertFalse(output.getAll().contains("frag"));
    }

    // --- Userinfo and JWKS

    @Test
    void aRefusedUserinfoCallLogsItsAddressAndStatus(CapturedOutput output) throws Exception {
        String state = begin();
        http.expect(requestTo(BASE + "/oauth2/token"))
                .andRespond(withSuccess(tokenBody(idToken()), MediaType.APPLICATION_JSON));
        http.expect(requestTo(BASE + "/oauth2/userinfo")).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_token\",\"error_description\":\""
                        + DESCRIPTION_SECRET + "\"}"));

        IdentityProviderException e = finish(state);

        assertEquals("The identity provider refused the userinfo request.", e.getMessage());
        assertTrue(output.getAll().contains("[userinfo request GET http://localhost:8080/oauth2/userinfo: HTTP 401, "
                + "error=invalid_token]"), output.getAll());
        assertNoSecrets(output);
    }

    @Test
    void aJwksCallThatFailsLogsItsAddressAndStatus(CapturedOutput output) throws Exception {
        String state = begin();
        MockRestServiceServer keys = jwks;
        keys.reset();
        keys.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/oauth2/jwks")).andRespond(withServerError()
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"server_error\",\"x\":\"" + BODY_SECRET
                        + "\"}"));
        http.expect(requestTo(BASE + "/oauth2/token"))
                .andRespond(withSuccess(tokenBody(idToken()), MediaType.APPLICATION_JSON));

        IdentityProviderException e = finish(state);

        assertEquals("The identity provider's answer could not be verified.", e.getMessage());
        assertTrue(output.getAll().contains("[JWKS request GET http://localhost:8080/oauth2/jwks: HTTP 500, "
                + "error=server_error]"), output.getAll());
        assertNoSecrets(output);
    }

    @Test
    void aJwksCallThatCannotConnectLogsTheExceptionClass(CapturedOutput output) throws Exception {
        String state = begin();
        jwks.reset();
        jwks.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/oauth2/jwks"))
                .andRespond(withException(new ConnectException("refused " + BODY_SECRET)));
        http.expect(requestTo(BASE + "/oauth2/token"))
                .andRespond(withSuccess(tokenBody(idToken()), MediaType.APPLICATION_JSON));

        finish(state);

        assertTrue(output.getAll().contains("[JWKS request GET http://localhost:8080/oauth2/jwks: "), output.getAll());
        assertTrue(output.getAll().contains("IOException") || output.getAll().contains("ConnectException"),
                output.getAll());
        assertNoSecrets(output);
    }

    @Test
    void aTokenThatIsRefusedLogsWhichTokenAndTheClassOnly(CapturedOutput output) throws Exception {
        String state = begin();
        String wrongIssuer = sign(claims("id").issuer("http://evil.example"));
        http.expect(requestTo(BASE + "/oauth2/token"))
                .andRespond(withSuccess(tokenBody(wrongIssuer), MediaType.APPLICATION_JSON));

        finish(state);

        String logs = output.getAll();
        assertTrue(logs.contains("[id_token check failed: JwtValidationException; failedChecks=[iss different], "
                + "category=JwtValidationException; body JWT; header {alg=PS256, kid=provider-1, typ=(none)}; "
                + "claimNames [aud, exp, iat, iss, jti, kind, sub]; iss=http://evil.example, aud=[coopaggregate], "), logs);
        assertTrue(logs.contains("; jwks [{kid=provider-1, alg=(none), kty=RSA}]]"), logs);
        assertFalse(logs.contains("subMatchesIdToken"), "only for the userinfo answer");
        assertFalse(logs.contains("pairwise-sub"), "the sub value is never logged");
        assertNoSecrets(output);
    }

    // --- The userinfo check

    private static final String NAME = "Uwimana Claudine";
    private static final String PHONE = "+250788123456";
    private static final String BIRTH = "1990/05/17";

    // The token call works and the id_token is right; the userinfo answer is the one given.
    private IdentityProviderException userinfoAnswer(String body, MediaType type) throws Exception {
        String state = begin();
        String id = idToken();
        http.expect(requestTo(BASE + "/oauth2/token"))
                .andRespond(withSuccess(tokenBody(id), MediaType.APPLICATION_JSON));
        http.expect(requestTo(BASE + "/oauth2/userinfo")).andRespond(withSuccess(body, type));
        return finish(state);
    }

    private static JWTClaimsSet.Builder person() {
        return claims("userinfo").claim("name", NAME).claim("phone_number", PHONE).claim("birthdate", BIRTH);
    }

    @Test
    void aUserinfoWithoutIssAndAudSaysSoAndNeverShowsTheDetails(CapturedOutput output) throws Exception {
        String userinfo = sign(person().issuer(null).audience((String) null));

        IdentityProviderException e = userinfoAnswer(userinfo, MediaType.valueOf("application/jwt"));

        String logs = output.getAll();
        assertEquals("The identity provider's answer could not be verified.", e.getMessage());
        assertTrue(logs.contains("[userinfo check failed: JwtValidationException; failedChecks=[iss missing, "
                + "aud missing], category=JwtValidationException; response HTTP 200, Content-Type application/jwt; "
                + "body JWT; header {alg=PS256, kid=provider-1, typ=(none)}; claimNames [birthdate, exp, iat, jti, "
                + "kind, name, phone_number, sub]; iss=(none), aud=(none), exp="), logs);
        assertTrue(logs.contains("; subMatchesIdToken=true; jwks [{kid=provider-1, alg=(none), kty=RSA}]]"), logs);
        assertNoPersonalData(output, userinfo);
    }

    @Test
    void aUserinfoSignedByAnUnknownKeySaysNoMatchingKey(CapturedOutput output) throws Exception {
        RSAKey stranger = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("stranger").generate();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.PS256).keyID("stranger").build(),
                person().build());
        jwt.sign(new RSASSASigner(stranger));

        userinfoAnswer(jwt.serialize(), MediaType.valueOf("application/jwt"));

        assertTrue(output.getAll().contains("failedChecks=[signature: no matching key], category=BadJwtException"),
                output.getAll());
        assertTrue(output.getAll().contains("header {alg=PS256, kid=stranger, typ=(none)}"), output.getAll());
        assertNoPersonalData(output, jwt.serialize());
    }

    @Test
    void aUserinfoWithTheRightKidButAnotherKeySaysTheSignatureDoesNotVerify(CapturedOutput output) throws Exception {
        RSAKey impostor = new com.nimbusds.jose.jwk.gen.RSAKeyGenerator(2048).keyID("provider-1").generate();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.PS256).keyID("provider-1").build(),
                person().build());
        jwt.sign(new RSASSASigner(impostor));

        userinfoAnswer(jwt.serialize(), MediaType.valueOf("application/jwt"));

        assertTrue(output.getAll().contains("failedChecks=[signature: does not verify], category=BadJwtException"),
                output.getAll());
        assertNoPersonalData(output, jwt.serialize());
    }

    @Test
    void aUserinfoSignedWithAnotherAlgorithmSaysTheAlgorithmIsNotAllowed(CapturedOutput output) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("provider-1")
                .type(com.nimbusds.jose.JOSEObjectType.JWT).build(), person().build());
        jwt.sign(new RSASSASigner(providerKey));

        userinfoAnswer(jwt.serialize(), MediaType.valueOf("application/jwt"));

        assertTrue(output.getAll().contains("failedChecks=[algorithm not allowed], category=BadJwtException"),
                output.getAll());
        assertTrue(output.getAll().contains("header {alg=RS256, kid=provider-1, typ=JWT}"), output.getAll());
        assertNoPersonalData(output, jwt.serialize());
    }

    @Test
    void anExpiredUserinfoSaysExpired(CapturedOutput output) throws Exception {
        String userinfo = sign(person().expirationTime(new Date(System.currentTimeMillis() - 600_000)));

        userinfoAnswer(userinfo, MediaType.valueOf("application/jwt"));

        assertTrue(output.getAll().contains("failedChecks=[expired], category=BadJwtException"),
                output.getAll());
        assertNoPersonalData(output, userinfo);
    }

    @Test
    void aUserinfoForAnotherPersonSaysTheSubDoesNotMatch(CapturedOutput output) throws Exception {
        String userinfo = sign(person().subject("someone-else"));

        userinfoAnswer(userinfo, MediaType.valueOf("application/jwt"));

        String logs = output.getAll();
        assertTrue(logs.contains("[userinfo check failed: failedChecks=[sub mismatch], category=(our own check); "
                + "response HTTP 200, Content-Type application/jwt; body JWT"), logs);
        assertTrue(logs.contains("subMatchesIdToken=false"), logs);
        assertFalse(logs.contains("someone-else"));
        assertNoPersonalData(output, userinfo);
    }

    @Test
    void aJsonUserinfoListsTheNamesOnly(CapturedOutput output) throws Exception {
        String body = "{\"sub\":\"pairwise-sub\",\"name\":\"" + NAME + "\",\"phone_number\":\"" + PHONE
                + "\",\"birthdate\":\"" + BIRTH + "\",\"iss\":\"" + BASE + "\"}";

        userinfoAnswer(body, MediaType.APPLICATION_JSON);

        String logs = output.getAll();
        assertTrue(logs.contains("[userinfo check failed: failedChecks=[format: JSON, not a signed JWT, aud missing, "
                + "exp missing], category=(our own check); response HTTP 200, Content-Type application/json; "
                + "body JSON; claimNames [birthdate, iss, name, phone_number, sub]; iss=http://localhost:8080, "
                + "aud=(none), exp=(none), iat=(none)"), logs);
        assertNoPersonalData(output, body);
    }

    // Never a claim value, the access token, the signature or the whole token.
    private void assertNoPersonalData(CapturedOutput output, String token) {
        String logs = output.getAll();
        for (String value : new String[] {NAME, "Uwimana", "Claudine", PHONE, "788123456", BIRTH, "1990",
                "pairwise-sub", ACCESS_TOKEN}) {
            assertFalse(logs.contains(value), "the log holds " + value);
        }
        assertFalse(logs.contains(token), "the whole token");
        String[] parts = token.split("\\.");
        if (parts.length == 3) {
            assertFalse(logs.contains(parts[1]), "the encoded claims");
            assertFalse(logs.contains(parts[2]), "the signature");
            assertFalse(logs.contains(parts[2].substring(0, 16)), "part of the signature");
        }
        assertNoSecrets(output);
    }

    // --- Startup

    @Test
    void theStartupLineShowsTheThreeAddressesWithoutAnyQuery(CapturedOutput output) {
        buildService(properties("http://localhost:8080/oauth2/token?client_secret=secret-in-the-url"));

        assertTrue(output.getAll().contains("Identity verification is on. Token: http://localhost:8080/oauth2/token, "
                + "userinfo: http://localhost:8080/oauth2/userinfo, JWKS: http://localhost:8080/oauth2/jwks"),
                output.getAll());
        assertFalse(output.getAll().contains("secret-in-the-url"));
    }

    @Test
    void theExceptionDetailIsNotPartOfTheMessage() {
        IdentityProviderException e = IdentityProviderException.failed("Fixed message.", "token request: HTTP 400");

        assertEquals("Fixed message.", e.getMessage());
        assertEquals("token request: HTTP 400", e.getDetail());
        assertNull(IdentityProviderException.failed("Fixed message.").getDetail());
        assertNull(IdentityProviderException.notConfigured("Off.").getDetail());
    }

    // --- Helpers

    // Starts a verification, remembers what must stay secret, and completes it against the fake provider.
    // --- The client assertion

    @Test
    void startupLogsHowTheAssertionIsSignedWithThePublicHalfOnly(CapturedOutput output) throws Exception {
        String thumbprint = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) cooperativeKey.getPublic())
                .build().computeThumbprint("SHA-256").toString();
        String modulus = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) cooperativeKey.getPublic())
                .build().getModulus().toString();

        assertTrue(output.getAll().contains("Client assertion: alg=PS256, thumbprint=" + thumbprint
                + ", kid=coopaggregate-key, kidEqualsThumbprint=false, modulusStart=" + modulus.substring(0, 8)
                + ", aud=http://localhost:8080, lifetimeSeconds=60"), output.getAll());
        assertFalse(output.getAll().contains(modulus.substring(0, 20)), "only the first 8 characters");
        assertFalse(output.getAll().contains("unused"), "never the key path");
        begin();
        assertNoSecrets(output);
    }

    @Test
    void startupSaysWhenTheKidIsTheThumbprint(CapturedOutput output) throws Exception {
        String thumbprint = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) cooperativeKey.getPublic())
                .build().computeThumbprint("SHA-256").toString();

        new MosipClient(properties(null), new MosipSigningKey((RSAPrivateKey) cooperativeKey.getPrivate(), thumbprint),
                RestClient.builder().build(), new RestTemplate(), Clock.systemUTC());

        assertTrue(output.getAll().contains("kid=" + thumbprint + ", kidEqualsThumbprint=true"), output.getAll());
    }

    @Test
    void aTokenCallRefusedWith401LogsTheDecodedAssertionButNeverItsSignature(CapturedOutput output)
            throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> sent = new java.util.concurrent.atomic.AtomicReference<>();
        http.expect(requestTo(BASE + "/oauth2/token"))
                .andExpect(request -> sent.set(java.net.URLDecoder.decode(
                        java.util.Arrays.stream(((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                .getBodyAsString().split("&"))
                                .filter(pair -> pair.startsWith("client_assertion="))
                                .findFirst().orElseThrow().substring("client_assertion=".length()),
                        java.nio.charset.StandardCharsets.UTF_8)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_client\"}"));

        fail();

        com.nimbusds.jwt.SignedJWT assertion = com.nimbusds.jwt.SignedJWT.parse(sent.get());
        com.nimbusds.jwt.JWTClaimsSet claims = assertion.getJWTClaimsSet();
        String logs = output.getAll();
        assertTrue(logs.contains("[token request POST http://localhost:8080/oauth2/token: HTTP 401, "
                + "error=invalid_client; assertion sent: header {alg=PS256, typ=(none), kid=coopaggregate-key}, "
                + "claims {iss=coopaggregate, sub=coopaggregate, aud=[http://localhost:8080], iat="
                + claims.getIssueTime().getTime() / 1000 + " (" + claims.getIssueTime().toInstant() + "), exp="
                + claims.getExpirationTime().getTime() / 1000 + " (" + claims.getExpirationTime().toInstant()
                + "), jti=" + claims.getJWTID() + "}]"), logs);
        String[] parts = sent.get().split("\\.");
        assertEquals(3, parts.length);
        assertFalse(logs.contains(parts[2]), "the signature is never logged");
        assertFalse(logs.contains(parts[2].substring(0, 16)), "not even part of it");
        assertFalse(logs.contains(sent.get()), "nor the whole token");
        assertFalse(logs.contains(parts[0]), "nor the encoded header");
        assertFalse(logs.contains(parts[1]), "nor the encoded claims");
        assertNoSecrets(output);
    }

    @Test
    void otherRefusalsDoNotLogTheAssertion(CapturedOutput output) {
        http.expect(requestTo(BASE + "/oauth2/token")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON).body("{\"error\":\"invalid_grant\"}"));

        fail();

        assertTrue(output.getAll().contains("HTTP 400, error=invalid_grant]"), output.getAll());
        assertFalse(output.getAll().contains("assertion sent"));
    }

    @Test
    void anUnreadableAssertionIsNotPrinted() {
        assertEquals("(unreadable)", Diagnostics.assertion("not-a-jwt"));
    }

    private IdentityProviderException fail() {
        return finish(begin());
    }

    private String begin() {
        String state = service.start(manager).state();
        IdentityVerification verification = stored.get(IdentityVerificationService.sha256(state));
        secrets.clear();
        secrets.addAll(List.of(CODE, state, verification.getNonce(), verification.getCodeVerifier(), BODY_SECRET,
                DESCRIPTION_SECRET, ACCESS_TOKEN,
                ((RSAPrivateKey) cooperativeKey.getPrivate()).getPrivateExponent().toString(),
                Base64.getEncoder().encodeToString(cooperativeKey.getPrivate().getEncoded()).substring(0, 60)));
        return state;
    }

    private IdentityProviderException finish(String state) {
        return assertThrows(IdentityProviderException.class, () -> service.complete(CODE, state, manager));
    }

    private final List<String> secrets = new ArrayList<>();

    private void assertNoSecrets(CapturedOutput output) {
        String logs = output.getAll();
        for (String secret : secrets) {
            assertFalse(logs.contains(secret), "the log holds a secret: " + secret.substring(0, 8) + "...");
        }
        assertFalse(logs.contains("client_assertion"));
        assertFalse(logs.contains("code_verifier"));
        assertFalse(logs.contains("Bearer"));
    }

    private static MosipProperties properties(String tokenEndpoint) {
        return new MosipProperties(true, BASE, "coopaggregate", "http://localhost:5173/identity/callback", "unused",
                "coopaggregate-key", null, null, null, null, tokenEndpoint, null, null);
    }

    private static String tokenBody(String idToken) {
        return "{\"access_token\":\"" + ACCESS_TOKEN + "\",\"token_type\":\"Bearer\",\"id_token\":\"" + idToken + "\"}";
    }

    private String idToken() throws Exception {
        return sign(claims("id").claim("nonce", currentNonce()));
    }

    private String currentNonce() {
        return stored.values().stream().findFirst().map(IdentityVerification::getNonce).orElse("n");
    }

    private static JWTClaimsSet.Builder claims(String kind) {
        return new JWTClaimsSet.Builder()
                .issuer(BASE)
                .subject("pairwise-sub")
                .audience("coopaggregate")
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .claim("kind", kind)
                .jwtID(UUID.randomUUID().toString());
    }

    private static String sign(JWTClaimsSet.Builder claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.PS256).keyID("provider-1").build(),
                claims.build());
        jwt.sign(new RSASSASigner(providerKey));
        return jwt.serialize();
    }
}
