package com.coopaggregate.share;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
import com.coopaggregate.delivery.ConfirmationStatus;

@WebMvcTest(ShareController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class ShareControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private SharingService sharingService;

    @MockitoBean
    private AuthService authService;

    private String bearerToken;

    @BeforeEach
    void setUp() {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("manager@example.com").issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        bearerToken = "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void unconfirmedDeliveriesAreListedInThe409() throws Exception {
        when(sharingService.preview(3L)).thenThrow(new UnconfirmedDeliveriesException("Lot A-2026-01 has "
                + "deliveries that are not confirmed yet: RCT-7K2Q (PENDING).", List.of(new UnconfirmedDelivery(42L,
                "RCT-7K2Q", 5L, "Uwimana Claudine", "M-0005", new BigDecimal("120.50"), ConfirmationStatus.PENDING))));

        mockMvc.perform(get("/api/lots/3/share-preview").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Lot A-2026-01 has deliveries that are not confirmed yet: RCT-7K2Q (PENDING)."))
                .andExpect(jsonPath("$.deliveries[0].receiptCode").value("RCT-7K2Q"))
                .andExpect(jsonPath("$.deliveries[0].memberName").value("Uwimana Claudine"))
                .andExpect(jsonPath("$.deliveries[0].quantityKg").value(120.50))
                .andExpect(jsonPath("$.deliveries[0].status").value("PENDING"));
    }
}
