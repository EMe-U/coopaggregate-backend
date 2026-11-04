package com.coopaggregate.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestClient;

import com.nimbusds.jose.jwk.RSAKey;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class MosipSettingsTest {

    private static KeyPair keyPair;

    @TempDir
    Path directory;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IdentityConfig.class)
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withBean(RestTemplateBuilder.class, RestTemplateBuilder::new);

    @BeforeAll
    static void createKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
    }

    // --- Settings

    @Test
    void endpointsFollowFromTheBaseUrl() {
        MosipProperties properties = properties("http://localhost:8080/", "the-key.pem");

        assertEquals("http://localhost:8080", properties.issuer());
        assertEquals("http://localhost:8080/oauth2/authorize", properties.authorizationUrl());
        assertEquals("http://localhost:8080/oauth2/token", properties.tokenUrl());
        assertEquals("http://localhost:8080/oauth2/userinfo", properties.userinfoUrl());
        assertEquals("http://localhost:8080/oauth2/jwks", properties.jwksUrl());
        assertEquals("http://localhost:8080", properties.audience());
    }

    @Test
    void mosipPublicAuthorizeUrlChangesOnlyTheAuthorizeUrl() {
        MosipProperties properties = withPublicAuthorizeUrl("https://abc.tunnel.example",
                " http://localhost:8080/oauth2/authorize ");

        assertEquals("http://localhost:8080/oauth2/authorize", properties.authorizationUrl());
        assertEquals("MOSIP_PUBLIC_AUTHORIZE_URL", properties.authorizationUrlSource());
        assertEquals("https://abc.tunnel.example/oauth2/token", properties.tokenUrl());
        assertEquals("https://abc.tunnel.example/oauth2/userinfo", properties.userinfoUrl());
        assertEquals("https://abc.tunnel.example/oauth2/jwks", properties.jwksUrl());
        assertEquals("https://abc.tunnel.example", properties.issuer());
        assertEquals("https://abc.tunnel.example", properties.audience());
        assertTrue(properties.insecure().isEmpty());
    }

    @Test
    void anEmptyMosipPublicAuthorizeUrlMeansTheBaseUrl() {
        MosipProperties properties = withPublicAuthorizeUrl("https://abc.tunnel.example", "");

        assertEquals("https://abc.tunnel.example/oauth2/authorize", properties.authorizationUrl());
        assertEquals("MOSIP_BASE_URL", properties.authorizationUrlSource());
    }

    @Test
    void mosipPublicAuthorizeUrlMayUsePlainHttpOnlyForLocalhost() {
        assertEquals(List.of("MOSIP_PUBLIC_AUTHORIZE_URL"),
                withPublicAuthorizeUrl("https://esignet.example", "http://esignet.example/authorize").insecure());
        assertTrue(withPublicAuthorizeUrl("https://esignet.example", "http://127.0.0.1:8080/oauth2/authorize")
                .insecure().isEmpty());
    }

    @Test
    void theAppStartsWithAPublicAuthorizeUrlOnLocalhostAndAnHttpsBase() throws IOException {
        Path file = directory.resolve("key.pem");
        Files.writeString(file, pem(keyPair.getPrivate().getEncoded()));

        runner.withPropertyValues(allSettings(file.toString()))
                .withPropertyValues("app.identity.mosip.base-url=https://abc.tunnel.example",
                        "app.identity.mosip.authorization-endpoint=http://localhost:8080/oauth2/authorize")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MosipProperties properties = context.getBean(MosipProperties.class);
                    assertEquals("http://localhost:8080/oauth2/authorize", properties.authorizationUrl());
                    assertEquals("https://abc.tunnel.example/oauth2/token", properties.tokenUrl());
                });
    }

    @Test
    void scopeAndAcrHaveTheDefaultsOfTheCooperative() {
        MosipProperties properties = properties("http://localhost:8080", "the-key.pem");

        assertEquals("openid", properties.scope());
        assertEquals("mosip:idp:acr:password", properties.acrValues());
        assertEquals(List.of(), properties.extraScopes());
    }

    @Test
    void scopesBeyondOpenidAndPhoneAreNamed() {
        MosipProperties properties = new MosipProperties(true, "http://localhost:8080", "coopaggregate",
                "http://localhost:5173/identity/callback", "the-key.pem", "kid", "openid  profile email phone", null,
                null, null, null, null, null, null, null, null, null, null, false);

        assertEquals(List.of("profile", "email"), properties.extraScopes());
    }

    @Test
    void missingSettingsAreListedByNameOnly() {
        MosipProperties properties = new MosipProperties(true, " ", null, "", null, "", null, null, null, null, null,
                null, null, null, null, null, null, null, false);

        assertEquals(List.of("MOSIP_BASE_URL", "MOSIP_CLIENT_ID", "MOSIP_REDIRECT_URI", "MOSIP_PRIVATE_KEY_PATH",
                "MOSIP_KEY_ID"), properties.missingRequired());
    }

    @Test
    void plainHttpIsOnlyAllowedForLocalhost() {
        assertTrue(properties("http://localhost:8080", "k").insecure().isEmpty());
        assertTrue(properties("http://127.0.0.1:8080", "k").insecure().isEmpty());
        assertTrue(properties("https://esignet.example", "k").insecure().isEmpty());
        assertEquals(List.of("MOSIP_BASE_URL"), properties("http://esignet.example", "k").insecure());
        assertEquals(List.of("MOSIP_BASE_URL"), properties("ftp://localhost", "k").insecure());
    }

    @Test
    void thePathOfTheKeyIsNeverPrinted() {
        assertFalse(properties("http://localhost:8080", "C:/secret-folder/the-key.pem").toString()
                .contains("secret-folder"));
    }

    // --- Key file

    @Test
    void aPemKeyIsRead() throws IOException {
        Path file = directory.resolve("key.pem");
        Files.writeString(file, pem(keyPair.getPrivate().getEncoded()), StandardCharsets.UTF_8);

        RSAPrivateKey key = MosipKeyLoader.load(file.toString());

        assertEquals(((RSAPrivateKey) keyPair.getPrivate()).getPrivateExponent(), key.getPrivateExponent());
    }

    @Test
    void aJwkKeyIsRead() throws IOException {
        Path file = directory.resolve("key.json");
        Files.writeString(file, jwk(true), StandardCharsets.UTF_8);

        RSAPrivateKey key = MosipKeyLoader.load(file.toString());

        assertEquals(((RSAPrivateKey) keyPair.getPrivate()).getPrivateExponent(), key.getPrivateExponent());
    }

    @Test
    void theFormatIsDetectedWhateverTheFileIsCalled() throws IOException {
        Path file = directory.resolve("whatever.txt");
        Files.writeString(file, "\n  " + jwk(true) + "\n", StandardCharsets.UTF_8);

        assertThat(MosipKeyLoader.load(file.toString())).isNotNull();
    }

    @Test
    void aJwkWithOnlyThePublicKeyIsRefused() throws IOException {
        Path file = directory.resolve("public.json");
        Files.writeString(file, jwk(false), StandardCharsets.UTF_8);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> MosipKeyLoader.load(file.toString()));

        assertTrue(e.getMessage().contains("only the public key"), e.getMessage());
    }

    @Test
    void anOldPkcs1PemIsExplained() throws IOException {
        Path file = directory.resolve("old.pem");
        Files.writeString(file, "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----\n");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> MosipKeyLoader.load(file.toString()));

        assertTrue(e.getMessage().contains("PKCS#1"), e.getMessage());
    }

    @Test
    void aFileThatIsNotAKeyIsRefusedWithoutShowingItsContentOrPath() throws IOException {
        Path file = directory.resolve("not-a-key-folder-name.txt");
        Files.writeString(file, "very secret words");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> MosipKeyLoader.load(file.toString()));

        assertFalse(e.getMessage().contains("very secret"), e.getMessage());
        assertFalse(e.getMessage().contains("not-a-key-folder-name"), e.getMessage());
    }

    @Test
    void aMissingFileIsRefusedWithoutShowingItsPath() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MosipKeyLoader.load(directory.resolve("nowhere-to-be-found.pem").toString()));

        assertTrue(e.getMessage().contains("cannot be read"), e.getMessage());
        assertFalse(e.getMessage().contains("nowhere-to-be-found"), e.getMessage());
    }

    @Test
    void aBrokenPemIsRefusedWithoutShowingItsContent() throws IOException {
        Path file = directory.resolve("broken.pem");
        Files.writeString(file, "-----BEGIN PRIVATE KEY-----\nc2VjcmV0\n-----END PRIVATE KEY-----\n");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> MosipKeyLoader.load(file.toString()));

        assertFalse(e.getMessage().contains("c2VjcmV0"), e.getMessage());
    }

    @Test
    void aKeyShorterThan2048BitsIsRefused() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        Path file = directory.resolve("short.pem");
        Files.writeString(file, pem(generator.generateKeyPair().getPrivate().getEncoded()));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> MosipKeyLoader.load(file.toString()));

        assertTrue(e.getMessage().contains("2048"), e.getMessage());
    }

    @Test
    void theSigningKeyNeverPrintsTheKey() {
        assertFalse(new MosipSigningKey((RSAPrivateKey) keyPair.getPrivate(), "kid-1").toString()
                .contains(((RSAPrivateKey) keyPair.getPrivate()).getPrivateExponent().toString()));
    }

    // --- Starting the app

    @Test
    void theAppStartsWithoutAnyMosipSettingWhileTheFeatureIsOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MosipSigningKey.class);
            assertThat(context).doesNotHaveBean(MosipClient.class);
        });
    }

    @Test
    void theAppRefusesToStartWhenTheFeatureIsOnButSettingsAreMissing() {
        runner.withPropertyValues("app.identity.mosip.enabled=true", "app.identity.mosip.client-id=coopaggregate")
                .run(context -> {
                    assertThat(context).hasFailed();
                    String message = rootMessage(context.getStartupFailure());
                    assertTrue(message.contains("MOSIP_BASE_URL, MOSIP_REDIRECT_URI, MOSIP_PRIVATE_KEY_PATH, MOSIP_KEY_ID"),
                            message);
                    assertFalse(message.contains("MOSIP_CLIENT_ID"), message);
                });
    }

    @Test
    void theAppRefusesToStartWhenTheKeyFileCannotBeUsed() {
        runner.withPropertyValues(allSettings(directory.resolve("not-there.pem").toString())).run(context -> {
            assertThat(context).hasFailed();
            String message = rootMessage(context.getStartupFailure());
            assertTrue(message.contains("MOSIP_PRIVATE_KEY_PATH"), message);
            assertFalse(message.contains("not-there"), message);
        });
    }

    @Test
    void theAppRefusesToStartWithPlainHttpToAnotherHost() throws IOException {
        Path file = directory.resolve("key.pem");
        Files.writeString(file, pem(keyPair.getPrivate().getEncoded()));

        runner.withPropertyValues(allSettings(file.toString()))
                .withPropertyValues("app.identity.mosip.base-url=http://esignet.example")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertTrue(rootMessage(context.getStartupFailure()).contains("must use https"));
                });
    }

    @Test
    void theAppStartsWhenEverythingIsThere() throws IOException {
        Path file = directory.resolve("key.pem");
        Files.writeString(file, pem(keyPair.getPrivate().getEncoded()));

        runner.withPropertyValues(allSettings(file.toString())).run(context -> {
            assertThat(context).hasNotFailed();
            assertEquals("coopaggregate-key", context.getBean(MosipSigningKey.class).keyId());
            assertThat(context).hasSingleBean(MosipClient.class);
        });
    }

    @Test
    void extraScopesAreNamedAtStartupButDoNotStopTheApp(
            org.springframework.boot.test.system.CapturedOutput output) throws IOException {
        Path file = directory.resolve("key.pem");
        Files.writeString(file, pem(keyPair.getPrivate().getEncoded()));

        runner.withPropertyValues(allSettings(file.toString()))
                .withPropertyValues("app.identity.mosip.scope=openid profile email").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertTrue(output.getAll().contains("MOSIP_SCOPE asks eSignet for more than the name and phone "
                            + "(profile, email); they are never used. Set MOSIP_SCOPE=openid or leave it out."));
                });
    }

    @Test
    void theDefaultScopeLogsNoWarning(org.springframework.boot.test.system.CapturedOutput output) throws IOException {
        Path file = directory.resolve("key.pem");
        Files.writeString(file, pem(keyPair.getPrivate().getEncoded()));

        runner.withPropertyValues(allSettings(file.toString()))
                .withPropertyValues("app.identity.mosip.scope=openid").run(context -> {
                    assertThat(context).hasNotFailed();
                    assertFalse(output.getAll().contains("MOSIP_SCOPE asks eSignet"));
                });
    }

    private static String[] allSettings(String keyPath) {
        return new String[] {"app.identity.mosip.enabled=true",
                "app.identity.mosip.base-url=http://localhost:8080",
                "app.identity.mosip.client-id=coopaggregate",
                "app.identity.mosip.redirect-uri=http://localhost:5173/identity/callback",
                "app.identity.mosip.private-key-path=" + keyPath,
                "app.identity.mosip.key-id=coopaggregate-key"};
    }

    private static MosipProperties properties(String baseUrl, String keyPath) {
        return new MosipProperties(true, baseUrl, "coopaggregate", "http://localhost:5173/identity/callback", keyPath,
                "coopaggregate-key", null, null, null, null, null, null, null, null, null, null, null, null, false);
    }

    private static MosipProperties withPublicAuthorizeUrl(String baseUrl, String publicAuthorizeUrl) {
        return new MosipProperties(true, baseUrl, "coopaggregate", "http://localhost:5173/identity/callback", "k",
                "coopaggregate-key", null, null, null, publicAuthorizeUrl, null, null, null, null, null, null, null,
                null, false);
    }

    private static String pem(byte[] der) {
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END PRIVATE KEY-----\n";
    }

    private static String jwk(boolean withPrivate) {
        RSAKey.Builder builder = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic()).keyID("coopaggregate-key");
        if (withPrivate) {
            builder.privateKey(keyPair.getPrivate());
        }
        return builder.build().toJSONString();
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
