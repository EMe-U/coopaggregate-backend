package com.coopaggregate.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MosipIdentityVerifierTest {

    private static final String KEY = "-----BEGIN PRIVATE KEY-----secret-----END PRIVATE KEY-----";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(IdentityConfig.class, MockIdentityVerifier.class, MosipIdentityVerifier.class);

    @Test
    void mockIsUsedWhileMosipIsOff() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MockIdentityVerifier.class);
            assertThat(context).doesNotHaveBean(MosipIdentityVerifier.class);
        });
    }

    @Test
    void mockIsSwitchedOffUnlessTheDevelopmentSettingIsOn() {
        runner.run(context -> {
            IdentityVerifier mock = context.getBean(IdentityVerifier.class);
            assertThrows(IdentityProviderException.class, () -> mock.authorizationUrl("state", "nonce"));
            assertThrows(IdentityProviderException.class, () -> mock.complete("code", "nonce"));
        });
        runner.withPropertyValues("app.identity.mock-allowed=true").run(context -> {
            IdentityVerifier mock = context.getBean(IdentityVerifier.class);
            assertEquals(VerificationMethod.MOCK, mock.complete("code", "nonce").method());
        });
    }

    @Test
    void realMosipIsNotAffectedByTheMockSetting() {
        for (String allowed : new String[] {"true", "false"}) {
            runner.withPropertyValues("app.identity.mosip.enabled=true",
                            "app.identity.mock-allowed=" + allowed,
                            "app.identity.mosip.base-url=https://esignet.example",
                            "app.identity.mosip.client-id=coop-client",
                            "app.identity.mosip.redirect-uri=https://app.example/verify",
                            "app.identity.mosip.private-key=" + KEY,
                            "app.identity.mosip.authorization-endpoint=https://esignet.example/authorize",
                            "app.identity.mosip.scope=openid")
                    .run(context -> {
                        IdentityVerifier verifier = context.getBean(IdentityVerifier.class);
                        assertEquals("MOSIP", verifier.name());
                        assertTrue(verifier.authorizationUrl("s", "n").startsWith("https://esignet.example/authorize?"));
                    });
        }
    }

    @Test
    void appRefusesToStartWhenMosipIsOnButSettingsAreMissing() {
        runner.withPropertyValues("app.identity.mosip.enabled=true", "app.identity.mosip.private-key=" + KEY)
                .run(context -> {
                    assertThat(context).hasFailed();
                    String message = rootMessage(context.getStartupFailure());
                    assertTrue(message.contains("MOSIP_BASE_URL, MOSIP_CLIENT_ID, MOSIP_REDIRECT_URI"), message);
                    assertFalse(message.contains("MOSIP_PRIVATE_KEY"), message);
                    assertFalse(message.contains("secret"), message);
                });
    }

    @Test
    void appStartsWithMosipWhenAllSettingsAreThere() {
        runner.withPropertyValues("app.identity.mosip.enabled=true",
                        "app.identity.mosip.base-url=https://esignet.example",
                        "app.identity.mosip.client-id=coop-client",
                        "app.identity.mosip.redirect-uri=https://app.example/verify",
                        "app.identity.mosip.private-key=" + KEY)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(MosipIdentityVerifier.class);
                    assertThat(context).doesNotHaveBean(MockIdentityVerifier.class);
                });
    }

    @Test
    void skeletonSaysNotConfiguredUntilTheValuesAreConfirmed() {
        MosipIdentityVerifier verifier = new MosipIdentityVerifier(properties(null, null, null));

        IdentityProviderException start = assertThrows(IdentityProviderException.class,
                () -> verifier.authorizationUrl("state", "nonce"));
        IdentityProviderException complete = assertThrows(IdentityProviderException.class,
                () -> verifier.complete("code", "nonce"));

        assertTrue(start.isNotConfigured());
        assertTrue(start.getMessage().contains("to confirm with the supervisor"));
        assertTrue(complete.isNotConfigured());
    }

    @Test
    void authorizationUrlUsesTheStandardOpenIdConnectParameters() {
        MosipIdentityVerifier verifier = new MosipIdentityVerifier(
                properties("https://esignet.example/authorize", "openid", null));

        String url = verifier.authorizationUrl("the-state", "the-nonce");

        assertEquals("https://esignet.example/authorize?response_type=code&client_id=coop-client"
                + "&redirect_uri=https://app.example/verify&scope=openid&state=the-state&nonce=the-nonce", url);
    }

    @Test
    void privateKeyIsNeverPrinted() {
        assertFalse(properties(null, null, null).toString().contains("secret"));
    }

    private static MosipProperties properties(String authorizationEndpoint, String scope, String acrValues) {
        return new MosipProperties(true, "https://esignet.example", "coop-client", "https://app.example/verify",
                KEY, authorizationEndpoint, null, scope, acrValues);
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
