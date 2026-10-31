package com.coopaggregate.identity;

import java.time.Instant;

/**
 * Where to send the member: authorizationUrl is eSignet's login page. The state must come back unchanged in the
 * callback before expiresAt.
 */
public record StartVerificationResponse(String provider, String authorizationUrl, String state, Instant expiresAt) {
}
