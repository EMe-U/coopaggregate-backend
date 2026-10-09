package com.coopaggregate.delivery;

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

import com.coopaggregate.auth.AuthService;
import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;
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
                .andExpect(jsonPath("$.deductionRwf").value(603));
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

    private static DeliveryResponse response() {
        return new DeliveryResponse(100L, "RCT-7K2Q", 5L, "Uwimana Claudine", "MEM-0005", 1L, "Big",
                3L, "A-2026-01", new BigDecimal("120.50"), 603L, Instant.parse("2026-10-09T07:00:00Z"));
    }
}
