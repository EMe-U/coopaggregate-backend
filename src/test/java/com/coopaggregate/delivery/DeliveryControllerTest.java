package com.coopaggregate.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.coopaggregate.auth.AuthService;
import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.manager.Manager;

@WebMvcTest(DeliveryController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class DeliveryControllerTest {

    private static final String VALID_BODY = """
            {"clientUuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "memberId": 5, "gradeId": 1, "quantityKg": 120.5}
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private DeliveryService deliveryService;

    @MockitoBean
    private AuthService authService;

    private String bearerToken;

    @BeforeEach
    void setUp() {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("manager@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        bearerToken = "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        when(authService.currentManager("manager@example.com")).thenReturn(new Manager());
    }

    @Test
    void newDeliveryReturns201() throws Exception {
        when(deliveryService.record(any(), any())).thenReturn(new RecordedDelivery(response(), true));

        mockMvc.perform(post("/api/deliveries").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptCode").value("RCT-7K2Q"))
                .andExpect(jsonPath("$.lotCode").value("A-2026-01"))
                .andExpect(jsonPath("$.deductionRwf").value(603))
                .andExpect(jsonPath("$.deliveredAt").value("2026-10-09T07:00:00Z"))
                .andExpect(jsonPath("$.syncedAt").value("2026-10-09T10:30:00Z"));
    }

    @Test
    void repeatedClientUuidReturns200() throws Exception {
        when(deliveryService.record(any(), any())).thenReturn(new RecordedDelivery(response(), false));

        mockMvc.perform(post("/api/deliveries").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(100));
    }

    @Test
    void invalidQuantityReturns400() throws Exception {
        mockMvc.perform(post("/api/deliveries").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"clientUuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "memberId": 5, "gradeId": 1,
                                 "quantityKg": 6000.123}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "quantityKg: must be at most 5000 kg; quantityKg: must have at most 2 decimals"));

        verifyNoInteractions(deliveryService);
    }

    @Test
    void missingFieldsReturn400() throws Exception {
        mockMvc.perform(post("/api/deliveries").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "clientUuid: is required; gradeId: is required; memberId: is required; quantityKg: is required"));
    }

    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/api/deliveries").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(deliveryService);
    }

    // Errors that will never succeed on retry must come back as 4xx with a message, never as 500.

    @Test
    void malformedClientUuidReturns400() throws Exception {
        postDelivery("""
                {"clientUuid": "not-a-uuid", "memberId": 5, "gradeId": 1, "quantityKg": 120.5}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "The request is missing data or has a value in the wrong format."));

        verifyNoInteractions(deliveryService);
    }

    @Test
    void deliveredAtWithoutTimeZoneReturns400() throws Exception {
        postDelivery("""
                {"clientUuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "memberId": 5, "gradeId": 1,
                 "quantityKg": 120.5, "deliveredAt": "2026-10-13T08:00:00"}
                """)
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deliveryService);
    }

    @Test
    void quantityThatIsNotANumberReturns400() throws Exception {
        postDelivery("""
                {"clientUuid": "7c9e6679-7425-40de-944b-e07fc1f90ae7", "memberId": 5, "gradeId": 1,
                 "quantityKg": "a lot"}
                """)
                .andExpect(status().isBadRequest());
    }

    @Test
    void inactiveMemberReturns400WithMessage() throws Exception {
        when(deliveryService.record(any(), any())).thenThrow(new BadRequestException(
                "Member M-0005 is inactive. Activate the member before recording a delivery."));

        postDelivery(VALID_BODY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Member M-0005 is inactive. Activate the member before recording a delivery."));
    }

    @Test
    void unknownGradeReturns400WithMessage() throws Exception {
        when(deliveryService.record(any(), any())).thenThrow(new BadRequestException("Grade 9 does not exist."));

        postDelivery(VALID_BODY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Grade 9 does not exist."));
    }

    @Test
    void deliveryTimeOutsideTheWindowReturns400WithMessage() throws Exception {
        when(deliveryService.record(any(), any())).thenThrow(new BadRequestException(
                "deliveredAt is more than 7 days ago. Offline deliveries must be synced within 7 days."));

        postDelivery(VALID_BODY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "deliveredAt is more than 7 days ago. Offline deliveries must be synced within 7 days."));
    }

    @Test
    void uniqueKeyClashReturns409InsteadOf500() throws Exception {
        when(deliveryService.record(any(), any())).thenThrow(
                new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        postDelivery(VALID_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("The request conflicts with existing data."));
    }

    @Test
    void confirmationCodeMustBeSixDigits() throws Exception {
        confirm("{\"code\": \"12345\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("code: must be 6 digits"));
        verifyNoInteractions(deliveryService);
    }

    @Test
    void wrongCodeReturns400WithTriesLeft() throws Exception {
        when(deliveryService.confirmWithCode(100L, "482193"))
                .thenThrow(new WrongCodeException("Wrong code. 2 tries left."));

        confirm("{\"code\": \"482193\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Wrong code. 2 tries left."));
    }

    @Test
    void confirmedDeliveryResponseNeverContainsTheCode() throws Exception {
        Delivery delivery = new Delivery();
        when(deliveryService.confirmWithCode(100L, "482193")).thenReturn(delivery);
        when(deliveryService.toResponse(delivery)).thenReturn(response());

        String body = confirm("{\"code\": \"482193\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("482193"));
        assertFalse(body.toLowerCase().contains("confirmationcode"));
    }

    @Test
    void codeIsMaskedWhenTheRequestIsPrinted() {
        assertEquals("ConfirmCodeRequest[code=******]", new ConfirmCodeRequest("482193").toString());
    }

    @Test
    void deliveryResponseHasNoCodeField() {
        for (var component : DeliveryResponse.class.getRecordComponents()) {
            assertFalse(component.getName().startsWith("confirmationCode"), component.getName());
        }
    }

    @Test
    void reviewNeedsANote() throws Exception {
        mockMvc.perform(post("/api/deliveries/100/review").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\": \" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("note: is required: say how the delivery was checked"));
        verifyNoInteractions(deliveryService);
    }

    private ResultActions confirm(String body) throws Exception {
        return mockMvc.perform(post("/api/deliveries/100/confirm").header(HttpHeaders.AUTHORIZATION, bearerToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions postDelivery(String body) throws Exception {
        return mockMvc.perform(post("/api/deliveries").header(HttpHeaders.AUTHORIZATION, bearerToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static DeliveryResponse response() {
        return new DeliveryResponse(100L, "RCT-7K2Q", 5L, "Uwimana Claudine", "M-0005", 1L, "Big",
                3L, "A-2026-01", new BigDecimal("120.50"), 603L, Instant.parse("2026-10-09T07:00:00Z"),
                Instant.parse("2026-10-09T10:30:00Z"), false, null, null, null,
                ConfirmationStatus.PENDING, null, null, 3, null, null, null, null, null);
    }
}
