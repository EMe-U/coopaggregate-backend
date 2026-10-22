package com.coopaggregate.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * For development, while MOSIP_ENABLED=false: every person is "verified" without a provider. The token is made
 * from the code, so sending the same code twice gives the same person, which is how the duplicate rule can be
 * tried locally.
 */
@Component
@ConditionalOnProperty(name = "app.identity.mosip.enabled", havingValue = "false", matchIfMissing = true)
public class MockIdentityVerifier implements IdentityVerifier {

    @Override
    public String authorizationUrl(String state, String nonce) {
        return null;
    }

    @Override
    public VerifiedIdentity complete(String code, String nonce) {
        return new VerifiedIdentity("mock-" + IdentityVerificationService.sha256(code).substring(0, 32),
                VerificationMethod.MOCK);
    }

    @Override
    public String name() {
        return "MOCK";
    }
}
