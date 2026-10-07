package com.coopaggregate.auth;

import java.time.Instant;

public record LoginResponse(String token, Instant expiresAt, String managerName) {
}
