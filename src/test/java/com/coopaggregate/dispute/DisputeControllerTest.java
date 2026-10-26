package com.coopaggregate.dispute;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

import com.coopaggregate.common.PageResponse;
import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;
import com.coopaggregate.delivery.DisputeReason;
import com.coopaggregate.error.BadRequestException;

@WebMvcTest(DisputeController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class DisputeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private DisputeService disputeService;

    private String bearerToken;

    @BeforeEach
    void setUp() {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("manager@example.com").issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        bearerToken = "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void disputesNeedALogin() throws Exception {
        mockMvc.perform(get("/api/disputes")).andExpect(status().isUnauthorized());
        verifyNoInteractions(disputeService);
    }

    @Test
    void openDisputesWithTotalsByDefault() throws Exception {
        when(disputeService.list(null, null, 0, 15)).thenReturn(new DisputesResponse(1, new BigDecimal("120.50"), 4,
                new PageResponse<>(List.of(new DisputeItem(42L, "RCT-7K2Q", 5L, "Uwimana Claudine", "M-0005", "Big",
                        "A-2026-01", new BigDecimal("120.50"), DisputeReason.WRONG_KG, "It was 100 kg",
                        Instant.parse("2026-10-23T08:00:00Z"), 3, true, null, null, null, null)), 0, 15, 1, 1)));

        mockMvc.perform(get("/api/disputes").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openDisputes").value(1))
                .andExpect(jsonPath("$.openKg").value(120.50))
                .andExpect(jsonPath("$.settledDisputes").value(4))
                .andExpect(jsonPath("$.disputes.size").value(15))
                .andExpect(jsonPath("$.disputes.content[0].receiptCode").value("RCT-7K2Q"))
                .andExpect(jsonPath("$.disputes.content[0].memberName").value("Uwimana Claudine"))
                .andExpect(jsonPath("$.disputes.content[0].memberNumber").value("M-0005"))
                .andExpect(jsonPath("$.disputes.content[0].gradeName").value("Big"))
                .andExpect(jsonPath("$.disputes.content[0].lotCode").value("A-2026-01"))
                .andExpect(jsonPath("$.disputes.content[0].reason").value("WRONG_KG"))
                .andExpect(jsonPath("$.disputes.content[0].daysOpen").value(3))
                .andExpect(jsonPath("$.disputes.content[0].open").value(true));
    }

    @Test
    void filtersAreHandedToTheService() throws Exception {
        when(disputeService.list("settled", "kalisa", 2, 30)).thenReturn(new DisputesResponse(0, BigDecimal.ZERO, 0,
                new PageResponse<>(List.of(), 2, 30, 0, 0)));

        mockMvc.perform(get("/api/disputes").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .param("status", "settled").param("search", "kalisa").param("page", "2").param("size", "30"))
                .andExpect(status().isOk());

        verify(disputeService).list("settled", "kalisa", 2, 30);
    }

    @Test
    void unknownStatusIs400() throws Exception {
        when(disputeService.list("closed", null, 0, 15))
                .thenThrow(new BadRequestException("status must be open or settled."));

        mockMvc.perform(get("/api/disputes").param("status", "closed").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status must be open or settled."));
    }
}
