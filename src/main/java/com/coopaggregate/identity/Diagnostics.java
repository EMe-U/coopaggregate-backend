package com.coopaggregate.identity;

import java.io.IOException;
import java.net.URI;
import java.util.Set;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One-line, safe descriptions of why a call to the identity provider failed, for the server log only. They hold the
 * target (scheme, host, port and path; never a query string), the HTTP status or the class name of the exception, and
 * the OAuth "error" code when it is one of the standard ones. Never a body, a message, a token or any other value.
 */
final class Diagnostics {

    // The error codes of RFC 6749, 6750 and 9449. Anything else in a body is ignored, whatever it says.
    private static final Set<String> OAUTH_ERRORS = Set.of("invalid_request", "invalid_client", "invalid_grant",
            "unauthorized_client", "unsupported_grant_type", "invalid_scope", "access_denied", "server_error",
            "temporarily_unavailable", "unsupported_response_type", "invalid_token", "insufficient_scope",
            "invalid_dpop_proof", "use_dpop_nonce",
            // eSignet's own: the member did not allow a claim the cooperative marked essential (the name or phone).
            "essential_consent_denied");
    private static final int MAX_BODY = 4096;
    private static final ObjectMapper JSON = new ObjectMapper();

    private Diagnostics() {
    }

    /** The error code if it is one of the known ones (trimmed, lower case), otherwise null. Never anything else. */
    static String knownError(String error) {
        if (error == null) {
            return null;
        }
        String code = error.trim().toLowerCase(java.util.Locale.ROOT);
        return OAUTH_ERRORS.contains(code) ? code : null;
    }

    /** scheme://host:port/path, without a query string, a fragment or user information. */
    static String safeUrl(String url) {
        try {
            URI uri = URI.create(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "(unreadable address)";
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "")
                    + (uri.getRawPath() == null ? "" : uri.getRawPath());
        } catch (RuntimeException e) {
            return "(unreadable address)";
        }
    }

    /** For a call made with the REST client, for example "token request POST http://localhost:8080/oauth2/token: ...". */
    static String call(String what, String method, String url, RestClientException e) {
        return what + " " + method + " " + safeUrl(url) + ": " + outcome(e);
    }

    /** For a token that was refused, or the JWKS fetch behind it that failed (it happens inside the JWT decoder). */
    static String token(String what, String jwksUrl, Throwable e) {
        // The fetch happens inside the decoder, wrapped several times: first look for the HTTP exception itself.
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof RestClientException rest) {
                return "JWKS request GET " + safeUrl(jwksUrl) + ": " + outcome(rest);
            }
        }
        for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (cause instanceof IOException || cause.getClass().getSimpleName().contains("KeySource")) {
                return "JWKS request GET " + safeUrl(jwksUrl) + ": " + describeFailure(cause);
            }
        }
        return what + " check failed: " + e.getClass().getSimpleName();
    }

    private static String outcome(RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            String error = errorCode(response);
            return "HTTP " + response.getStatusCode().value() + (error == null ? "" : ", error=" + error);
        }
        return describeFailure(e.getCause() == null ? e : e.getCause());
    }

    // The exception class, and the deepest cause when that is another class (a SSLHandshakeException caused by a
    // CertPathBuilderException). Names only: their messages can hold addresses and anything the other side sent.
    private static String describeFailure(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String name = failure.getClass().getSimpleName();
        return root == failure ? name : name + " (cause: " + root.getClass().getSimpleName() + ")";
    }

    private static String errorCode(RestClientResponseException response) {
        try {
            String body = response.getResponseBodyAsString();
            if (body == null || body.isBlank() || body.length() > MAX_BODY) {
                return null;
            }
            JsonNode error = JSON.readTree(body).path("error");
            return error.isTextual() && OAUTH_ERRORS.contains(error.asText()) ? error.asText() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
