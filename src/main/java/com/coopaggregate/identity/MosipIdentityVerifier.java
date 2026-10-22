package com.coopaggregate.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * MOSIP eSignet, used when MOSIP_ENABLED=true. A skeleton of the standard OpenID Connect authorization-code flow
 * with a client that signs its requests with a private key (private_key_jwt). Everything MOSIP-specific is TO
 * CONFIRM WITH THE SUPERVISOR (see MosipProperties); until then the calls throw "not configured" (503).
 */
@Component
@ConditionalOnProperty(name = "app.identity.mosip.enabled", havingValue = "true")
public class MosipIdentityVerifier implements IdentityVerifier {

    private final MosipProperties properties;

    public MosipIdentityVerifier(MosipProperties properties) {
        // Refuse to start rather than fail on the first member. Only the names of missing settings are shown.
        if (!properties.missingRequired().isEmpty()) {
            throw new IllegalStateException("MOSIP_ENABLED=true but these settings are missing: "
                    + String.join(", ", properties.missingRequired())
                    + ". Set them as environment variables, or set MOSIP_ENABLED=false to use the mock.");
        }
        this.properties = properties;
    }

    /** Step 1 (standard OpenID Connect): the authorize URL with the client, redirect, scope, state and nonce. */
    @Override
    public String authorizationUrl(String state, String nonce) {
        if (MosipProperties.isBlank(properties.authorizationEndpoint()) || MosipProperties.isBlank(properties.scope())) {
            throw notConfigured("the authorization endpoint and scope");
        }
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(properties.authorizationEndpoint())
                .queryParam("response_type", "code")
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", properties.redirectUri())
                .queryParam("scope", properties.scope())
                .queryParam("state", state)
                .queryParam("nonce", nonce);
        if (!MosipProperties.isBlank(properties.acrValues())) {
            url.queryParam("acr_values", properties.acrValues());
        }
        return url.encode().build().toUriString();
    }

    /**
     * Step 2, still to build once the sandbox details are confirmed:
     * 1. POST the code to the token endpoint, authenticated with a client assertion JWT signed with
     *    MOSIP_PRIVATE_KEY (grant_type=authorization_code, redirect_uri, client_assertion_type
     *    urn:ietf:params:oauth:client-assertion-type:jwt-bearer).
     * 2. Check the ID token's signature against the provider's published keys, its issuer, audience (client id),
     *    expiry and that its nonce equals the one sent in step 1.
     * 3. Return its "sub" claim as the pseudonymous id, and the method (OTP or FACE) from the claim to confirm.
     * Never log or store the code, the tokens or the raw response.
     */
    @Override
    public VerifiedIdentity complete(String code, String nonce) {
        if (MosipProperties.isBlank(properties.tokenEndpoint())) {
            throw notConfigured("the token endpoint");
        }
        throw IdentityProviderException.notConfigured("MOSIP verification is not built yet: the token exchange "
                + "waits for the sandbox details (to confirm with the supervisor).");
    }

    @Override
    public String name() {
        return "MOSIP";
    }

    private static IdentityProviderException notConfigured(String what) {
        return IdentityProviderException.notConfigured("MOSIP verification is not configured yet: " + what
                + " are still to confirm with the supervisor.");
    }
}
