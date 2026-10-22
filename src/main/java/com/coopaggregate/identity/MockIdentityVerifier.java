package com.coopaggregate.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * For development, while MOSIP_ENABLED=false: every person is "verified" without a provider, so it must not look
 * like a real check. It only works when MOCK_VERIFICATION_ALLOWED=true (local development, never Render);
 * otherwise start and callback are refused with 503. The token is made from the code, so sending the same code
 * twice gives the same person, which is how the duplicate rule can be tried locally.
 */
@Component
@ConditionalOnProperty(name = "app.identity.mosip.enabled", havingValue = "false", matchIfMissing = true)
public class MockIdentityVerifier implements IdentityVerifier {

    private static final Logger log = LoggerFactory.getLogger(MockIdentityVerifier.class);

    private final boolean allowed;

    public MockIdentityVerifier(@Value("${app.identity.mock-allowed:false}") boolean allowed) {
        this.allowed = allowed;
        if (allowed) {
            log.warn("MOCK_VERIFICATION_ALLOWED is on: anyone can be 'verified' without a real check. "
                    + "Local development only.");
        } else {
            log.info("Test verification is switched off. Set MOCK_VERIFICATION_ALLOWED=true to use it in local "
                    + "development.");
        }
    }

    @Override
    public String authorizationUrl(String state, String nonce) {
        checkAllowed();
        return null;
    }

    @Override
    public VerifiedIdentity complete(String code, String nonce) {
        checkAllowed();
        return new VerifiedIdentity("mock-" + IdentityVerificationService.sha256(code).substring(0, 32),
                VerificationMethod.MOCK);
    }

    @Override
    public String name() {
        return "MOCK";
    }

    // 503, like a provider that is not configured: the feature is switched off, not the request wrong. The
    // message goes to the manager, so how to switch it on is only in the startup log and the README.
    private void checkAllowed() {
        if (!allowed) {
            throw IdentityProviderException.notConfigured("Test verification is switched off.");
        }
    }
}
