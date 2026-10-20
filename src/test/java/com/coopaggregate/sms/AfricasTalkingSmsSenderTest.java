package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

class AfricasTalkingSmsSenderTest {

    private static final String SANDBOX_URL = "https://api.sandbox.africastalking.com/version1/messaging";

    private MockRestServiceServer server;
    private AfricasTalkingSmsSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new AfricasTalkingSmsSender(builder, "sandbox", "test-key", "COOP");
    }

    @Test
    void sendsTheMessageToTheSandbox() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", "sandbox");
        form.add("to", "+250788123456");
        form.add("message", "Hello");
        form.add("from", "COOP");
        server.expect(requestTo(SANDBOX_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("apiKey", "test-key"))
                .andExpect(content().formData(form))
                .andRespond(withSuccess(accepted(101, "Success"), MediaType.APPLICATION_JSON));

        sender.send("+250788123456", "Hello");

        server.verify();
    }

    @Test
    void rejectedMessageThrows() {
        server.expect(requestTo(SANDBOX_URL))
                .andRespond(withSuccess(accepted(405, "InsufficientBalance"), MediaType.APPLICATION_JSON));

        SmsSendException e = assertThrows(SmsSendException.class, () -> sender.send("+250788123456", "Hello"));

        assertEquals("Africa's Talking did not accept the message: InsufficientBalance", e.getMessage());
    }

    @Test
    void serverErrorThrows() {
        server.expect(requestTo(SANDBOX_URL)).andRespond(withServerError());

        assertThrows(SmsSendException.class, () -> sender.send("+250788123456", "Hello"));
    }

    @Test
    void missingKeysStopTheAppFromStarting() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new AfricasTalkingSmsSender(RestClient.builder(), "sandbox", "", ""));

        assertEquals("SMS_PROVIDER is africastalking, so AT_USERNAME and AT_API_KEY must be set.", e.getMessage());
    }

    private static String accepted(int statusCode, String status) {
        return """
                {"SMSMessageData": {"Message": "Sent to 1/1", "Recipients": [
                  {"statusCode": %d, "number": "+250788123456", "status": "%s", "cost": "RWF 15.0000",
                   "messageId": "ATXid_1"}]}}
                """.formatted(statusCode, status);
    }
}
