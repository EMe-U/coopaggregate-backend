package com.coopaggregate.identity;

import java.time.Instant;

/**
 * Where to send the member. authorizationUrl is null for the mock: then call the callback straight away with any
 * code. The state must come back unchanged in the callback before expiresAt.
 */
public record StartVerificationResponse(String provider, String authorizationUrl, String state, Instant expiresAt) {
}
