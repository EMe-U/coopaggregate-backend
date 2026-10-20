package com.coopaggregate.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sends SMS with Africa's Talking. The username "sandbox" uses their sandbox, where messages appear in the
 * simulator instead of on real phones. Keys come only from the environment.
 */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "africastalking")
public class AfricasTalkingSmsSender implements SmsSender {

    private static final String LIVE_URL = "https://api.africastalking.com/version1/messaging";
    private static final String SANDBOX_URL = "https://api.sandbox.africastalking.com/version1/messaging";

    private final RestClient restClient;
    private final String username;
    private final String senderId;

    public AfricasTalkingSmsSender(RestClient.Builder restClientBuilder,
                                   @Value("${app.sms.at.username}") String username,
                                   @Value("${app.sms.at.api-key}") String apiKey,
                                   @Value("${app.sms.at.sender-id}") String senderId) {
        if (username.isBlank() || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "SMS_PROVIDER is africastalking, so AT_USERNAME and AT_API_KEY must be set.");
        }
        // Timeouts come from spring.http.client.* in application.properties.
        this.restClient = restClientBuilder
                .baseUrl("sandbox".equals(username) ? SANDBOX_URL : LIVE_URL)
                .defaultHeader("apiKey", apiKey)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.username = username;
        this.senderId = senderId;
    }

    @Override
    public void send(String phone, String message) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", username);
        form.add("to", phone);
        form.add("message", message);
        if (!senderId.isBlank()) {
            form.add("from", senderId);
        }

        JsonNode response;
        try {
            response = restClient.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException e) {
            throw new SmsSendException("Africa's Talking request failed: " + e.getMessage(), e);
        }

        // Status codes 100 (processed), 101 (sent) and 102 (queued) mean the message was accepted.
        JsonNode recipient = response == null ? null : response.path("SMSMessageData").path("Recipients").path(0);
        int statusCode = recipient == null ? 0 : recipient.path("statusCode").asInt(0);
        if (statusCode < 100 || statusCode > 102) {
            String status = recipient == null || recipient.isMissingNode()
                    ? String.valueOf(response)
                    : recipient.path("status").asText();
            throw new SmsSendException("Africa's Talking did not accept the message: " + status);
        }
    }

    @Override
    public String name() {
        return "africastalking";
    }
}
