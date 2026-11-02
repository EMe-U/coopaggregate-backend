package com.coopaggregate.sms;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.coopaggregate.common.RwandanPhoneNumber;

/**
 * Sends SMS through the Traccar SMS Gateway: an Android phone with a SIM. The gateway accepting a message does not
 * mean it was delivered, and the phone must stay on and online. The token comes only from the environment. Nothing
 * secret is logged or put in an exception: not the token, not the message (it holds the one-time code), and the
 * phone number only with its last three digits.
 */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "traccar")
public class TraccarSmsSender implements SmsSender {

    static final String DEFAULT_URL = "https://www.traccar.org/sms/";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private static final Logger log = LoggerFactory.getLogger(TraccarSmsSender.class);
    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");

    private final RestClient restClient;
    private final String url;
    private final String token;

    @Autowired
    public TraccarSmsSender(RestClient.Builder restClientBuilder,
                            @Value("${app.sms.traccar.url:}") String url,
                            @Value("${app.sms.traccar.token:}") String token) {
        this(restClientBuilder.requestFactory(requestFactory()).build(), url, token);
    }

    // For tests, which bring a client bound to a mock server.
    TraccarSmsSender(RestClient restClient, String url, String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("SMS_PROVIDER is traccar, so SMS_TRACCAR_TOKEN must be set.");
        }
        if (token.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalStateException("SMS_TRACCAR_TOKEN must be a single line.");
        }
        this.url = url == null || url.isBlank() ? DEFAULT_URL : url.trim();
        if (!acceptable(this.url)) {
            throw new IllegalStateException(
                    "SMS_TRACCAR_URL must be an https URL (http is accepted only for localhost).");
        }
        this.restClient = restClient;
        this.token = token.trim();
    }

    // The timeouts of this provider, whatever spring.http.client.* says. A redirect is never followed: the
    // Authorization header must only go where it was configured to go.
    static HttpClient httpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    static JdkClientHttpRequestFactory requestFactory() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient());
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    static boolean acceptable(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null || uri.getUserInfo() != null) {
                return false;
            }
            return "https".equalsIgnoreCase(uri.getScheme())
                    || ("http".equalsIgnoreCase(uri.getScheme()) && LOCAL_HOSTS.contains(host.toLowerCase()));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void send(String phone, String message) {
        String to;
        try {
            to = RwandanPhoneNumber.normalize(phone);
        } catch (IllegalArgumentException e) {
            // The exception text holds the number; the log and the error saved with the message must not.
            log.warn("Traccar SMS to {} not sent: not a Rwandan mobile number", mask(phone));
            throw new SmsSendException("Traccar SMS Gateway: the number is not a Rwandan mobile number.");
        }

        try {
            // Any answer that is not 2xx failed, a redirect included. "Accepted" is not "delivered".
            restClient.post()
                    .uri(url)
                    .header(HttpHeaders.AUTHORIZATION, token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("to", to, "message", message))
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new SmsSendException(failure(to, "HTTP " + response.getStatusCode().value()));
                        }
                        return Boolean.TRUE;
                    });
        } catch (SmsSendException e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw new SmsSendException(failure(to, "HTTP " + e.getStatusCode().value()));
        } catch (RestClientException | IllegalArgumentException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new SmsSendException(failure(to, cause.getClass().getSimpleName()));
        }
    }

    // One WARN line and the text kept with the message: the status or the exception class, never a message.
    private static String failure(String to, String reason) {
        log.warn("Traccar SMS to {} failed: {}", mask(to), reason);
        return "Traccar SMS Gateway request failed: " + reason;
    }

    static String mask(String phone) {
        if (phone == null || phone.isEmpty()) {
            return "(none)";
        }
        int keep = Math.min(3, phone.length());
        return "*".repeat(Math.min(phone.length() - keep, 10)) + phone.substring(phone.length() - keep);
    }

    @Override
    public String name() {
        return "traccar";
    }
}
