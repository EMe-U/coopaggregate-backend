package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

@ExtendWith(OutputCaptureExtension.class)
class TraccarSmsSenderTest {

    private static final String URL = "https://www.traccar.org/sms/";
    private static final String TOKEN = "tr4ccar-secret-token-9f3a";
    private static final String CODE = "482915";
    private static final String MESSAGE = "Your delivery code is " + CODE + ".";

    private MockRestServiceServer server;
    private TraccarSmsSender sender;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new TraccarSmsSender(builder.build(), URL, TOKEN);
    }

    @Test
    void sendsToTheGatewayWithTheRawTokenAndAJsonBody() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", TOKEN))
                .andExpect(header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.to").value("+250788123456"))
                .andExpect(jsonPath("$.message").value(MESSAGE))
                .andRespond(withSuccess());

        sender.send("+250788123456", MESSAGE);

        server.verify();
    }

    @Test
    void theBodyHasOnlyTheNumberAndTheMessage() {
        server.expect(requestTo(URL))
                .andExpect(content().json("{\"to\":\"+250788123456\",\"message\":\"Hi\"}", true))
                .andRespond(withSuccess());

        sender.send("+250788123456", "Hi");

        server.verify();
    }

    @Test
    void everyAcceptedFormOfTheNumberGoesOutAsPlus2507() {
        for (String number : new String[] {"0788123456", "250788123456", "+250788123456"}) {
            server.reset();
            server.expect(requestTo(URL)).andExpect(jsonPath("$.to").value("+250788123456"))
                    .andRespond(withSuccess());

            sender.send(number, "Hi");

            server.verify();
        }
    }

    @Test
    void aNumberThatIsNotARwandanMobileIsRefusedAndNothingIsSent(CapturedOutput output) {
        for (String number : new String[] {"+254712345678", "0711234567", "078812345", "abc", "", null}) {
            SmsSendException e = assertThrows(SmsSendException.class, () -> sender.send(number, MESSAGE));

            assertEquals("Traccar SMS Gateway: the number is not a Rwandan mobile number.", e.getMessage());
        }
        server.verify();
        assertFalse(output.getAll().contains("254712345678"), output.getAll());
    }

    @Test
    void anAnswerThatIsNotSuccessIsAFailedSend() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED,
                HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND, HttpStatus.TOO_MANY_REQUESTS,
                HttpStatus.INTERNAL_SERVER_ERROR, HttpStatus.BAD_GATEWAY, HttpStatus.FOUND}) {
            server.reset();
            server.expect(requestTo(URL)).andRespond(withStatus(status));

            SmsSendException e = assertThrows(SmsSendException.class, () -> sender.send("+250788123456", MESSAGE));

            assertEquals("Traccar SMS Gateway request failed: HTTP " + status.value(), e.getMessage());
        }
    }

    @Test
    void anyTwoHundredAnswerIsAccepted() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.ACCEPTED));

        sender.send("+250788123456", MESSAGE);

        server.verify();
    }

    @Test
    void aNetworkFailureIsAFailedSendNamedByItsClassOnly() {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new IOException("connection reset by 10.1.2.3 for " + TOKEN + " " + MESSAGE);
        });

        SmsSendException e = assertThrows(SmsSendException.class, () -> sender.send("+250788123456", MESSAGE));

        assertEquals("Traccar SMS Gateway request failed: IOException", e.getMessage());
        assertEquals(null, e.getCause());
    }

    @Test
    void doesNotRetryInsideTheProvider() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThrows(SmsSendException.class, () -> sender.send("+250788123456", MESSAGE));

        server.verify(); // exactly one request was expected; a second would have failed the mock
    }

    @Test
    void aFailureLogsOneWarnLineWithTheStatusAndAMaskedNumber(CapturedOutput output) {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThrows(SmsSendException.class, () -> sender.send("+250788123456", MESSAGE));

        String line = output.getAll().lines().filter(l -> l.contains("Traccar SMS to")).findFirst().orElseThrow();
        assertTrue(line.contains("WARN"), line);
        assertTrue(line.contains("failed: HTTP 401"), line);
        assertTrue(line.contains("*456"), line);
        assertEquals(1, output.getAll().lines().filter(l -> l.contains("Traccar SMS to")).count());
    }

    @Test
    void neitherTheTokenNorTheCodeNorTheFullNumberIsEverLogged(CapturedOutput output) {
        server.expect(requestTo(URL)).andRespond(request -> {
            throw new IOException("failed for " + TOKEN + " to +250788123456: " + MESSAGE);
        });
        assertThrows(SmsSendException.class, () -> sender.send("+250788123456", MESSAGE));
        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN)
                .body("{\"error\":\"" + TOKEN + " " + MESSAGE + "\"}").contentType(MediaType.APPLICATION_JSON));
        assertThrows(SmsSendException.class, () -> sender.send("0788123456", MESSAGE));
        assertThrows(SmsSendException.class, () -> sender.send("+254700123456", MESSAGE));

        String logs = output.getAll();
        assertFalse(logs.contains(TOKEN), logs);
        assertFalse(logs.contains(CODE), logs);
        assertFalse(logs.contains("Your delivery code"), logs);
        assertFalse(logs.contains("Authorization"), logs);
        assertFalse(logs.contains("788123456"), logs);
        assertFalse(logs.contains("254700123456"), logs);
        assertTrue(logs.contains("456"), logs);
    }

    @Test
    void namesItselfTraccar() {
        assertEquals("traccar", sender.name());
    }

    // ---- configuration

    @Test
    void aMissingTokenStopsTheAppFromStartingAndNamesOnlyTheVariable() {
        for (String token : new String[] {"", "   ", null}) {
            IllegalStateException e = assertThrows(IllegalStateException.class,
                    () -> new TraccarSmsSender(RestClient.builder(), URL, token));

            assertEquals("SMS_PROVIDER is traccar, so SMS_TRACCAR_TOKEN must be set.", e.getMessage());
        }
    }

    @Test
    void aTokenWithALineBreakIsRefusedWithoutShowingIt() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TraccarSmsSender(RestClient.builder(), URL, "abc\r\nX-Evil: 1"));

        assertEquals("SMS_TRACCAR_TOKEN must be a single line.", e.getMessage());
    }

    @Test
    void theDefaultUrlIsUsedWhenNoneIsGiven() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer mock = MockRestServiceServer.bindTo(builder).build();
        TraccarSmsSender defaulted = new TraccarSmsSender(builder.build(), "  ", TOKEN);
        mock.expect(requestTo(URL)).andRespond(withSuccess());

        defaulted.send("+250788123456", "Hi");

        mock.verify();
    }

    @Test
    void httpIsAcceptedOnlyForLocalhost() {
        for (String url : new String[] {"http://localhost:8082/sms/", "http://127.0.0.1/sms", "http://[::1]:8082/",
                "https://www.traccar.org/sms/", "https://sms.example.org"}) {
            assertTrue(TraccarSmsSender.acceptable(url), url);
        }
        for (String url : new String[] {"http://www.traccar.org/sms/", "http://192.168.1.5:8082/",
                "http://localhost.evil.com/", "ftp://localhost/", "https://user:pw@www.traccar.org/", "//host/x",
                "not a url", "https:///x"}) {
            assertFalse(TraccarSmsSender.acceptable(url), url);
        }
    }

    @Test
    void anUnacceptableUrlStopsTheAppFromStartingAndNamesOnlyTheVariable() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> new TraccarSmsSender(RestClient.builder(), "http://www.traccar.org/sms/?secret=abc", TOKEN));

        assertEquals("SMS_TRACCAR_URL must be an https URL (http is accepted only for localhost).", e.getMessage());
    }

    @Test
    void connectsWithin3SecondsReadsWithin10AndNeverFollowsARedirect() {
        HttpClient client = TraccarSmsSender.httpClient();
        JdkClientHttpRequestFactory factory = TraccarSmsSender.requestFactory();

        assertEquals(Duration.ofSeconds(3), client.connectTimeout().orElseThrow());
        assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
        assertEquals(Duration.ofSeconds(10), ReflectionTestUtils.getField(factory, "readTimeout"));
    }

    // ---- provider selection, the same way as the other providers

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(RestClient.Builder.class, RestClient::builder)
            .withUserConfiguration(TraccarSmsSender.class, LogSmsSender.class);

    @Test
    void isUsedOnlyWhenTheProviderIsTraccar() {
        runner.withPropertyValues("app.sms.provider=traccar", "app.sms.traccar.token=" + TOKEN)
                .run(context -> {
                    assertTrue(context.getBeansOfType(TraccarSmsSender.class).size() == 1);
                    assertTrue(context.getBeansOfType(LogSmsSender.class).isEmpty());
                });
        runner.withPropertyValues("app.sms.provider=log")
                .run(context -> assertTrue(context.getBeansOfType(TraccarSmsSender.class).isEmpty()));
        runner.run(context -> assertTrue(context.getBeansOfType(TraccarSmsSender.class).isEmpty()));
    }

    @Test
    void theAppDoesNotStartWithTheTraccarProviderAndNoToken() {
        runner.withPropertyValues("app.sms.provider=traccar", "app.sms.traccar.token=")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    Throwable root = context.getStartupFailure();
                    while (root.getCause() != null) {
                        root = root.getCause();
                    }
                    assertEquals("SMS_PROVIDER is traccar, so SMS_TRACCAR_TOKEN must be set.", root.getMessage());
                });
    }

    @Test
    void showingCodesStillWorksOnlyWithTheLogProvider() {
        new ApplicationContextRunner().withUserConfiguration(SmsCodeLoggingGuard.class)
                .withPropertyValues("app.sms.provider=traccar", "app.sms.log-show-codes=true")
                .run(context -> assertTrue(context.getStartupFailure() != null));
        new ApplicationContextRunner().withUserConfiguration(SmsCodeLoggingGuard.class)
                .withPropertyValues("app.sms.provider=log", "app.sms.log-show-codes=true")
                .run(context -> assertTrue(context.getStartupFailure() == null));
    }
}
