package com.coopaggregate.batch;

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
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.lot.LotStatus;

@WebMvcTest(BatchController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class BatchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private BatchService batchService;

    private String bearerToken;

    @BeforeEach
    void setUp() {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("manager@example.com").issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        bearerToken = "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void batchesNeedALogin() throws Exception {
        mockMvc.perform(get("/api/members/5/batches")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/grades/1/batches")).andExpect(status().isUnauthorized());
        verifyNoInteractions(batchService);
    }

    @Test
    void memberBatchesWithTotals() throws Exception {
        when(batchService.memberBatches(5L, "unsold", 0, 20)).thenReturn(new MemberBatchesResponse(5L, "M-0005",
                "Uwimana Claudine", new BatchTotals(new BigDecimal("120.50"), new BigDecimal("100.00"),
                        new BigDecimal("0.50"), new BigDecimal("20.00")),
                new BatchEarnings(30000L, 500L),
                new PageResponse<>(List.of(batch()), 0, 20, 1, 1)));

        mockMvc.perform(get("/api/members/5/batches").param("status", "unsold")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberNumber").value("M-0005"))
                .andExpect(jsonPath("$.totals.receivedKg").value(120.50))
                .andExpect(jsonPath("$.totals.remainingKg").value(20.00))
                .andExpect(jsonPath("$.batches.content[0].receiptCode").value("RCT-7K2Q"))
                .andExpect(jsonPath("$.batches.content[0].soldKg").value(100.00))
                .andExpect(jsonPath("$.batches.content[0].remainingKg").value(20.00))
                .andExpect(jsonPath("$.batches.content[0].deliveredAt").value("2026-10-19T08:00:00Z"))
                .andExpect(jsonPath("$.batches.content[0].earnedRwf").value(29500))
                .andExpect(jsonPath("$.batches.content[0].payment").value("BATCH"))
                .andExpect(jsonPath("$.earnings.earnedRwf").value(29500));
    }

    @Test
    void gradeBatchesUseTheDefaultPage() throws Exception {
        when(batchService.gradeBatches(1L, 0, 20)).thenReturn(new GradeBatchesResponse(1L, "Big",
                new BigDecimal("20.00"), new PageResponse<>(List.of(batch()), 0, 20, 1, 1)));

        mockMvc.perform(get("/api/grades/1/batches").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gradeName").value("Big"))
                .andExpect(jsonPath("$.remainingKg").value(20.00))
                .andExpect(jsonPath("$.batches.content[0].lotStatus").value("CLOSED"));
        verify(batchService).gradeBatches(1L, 0, 20);
    }

    private static BatchResponse batch() {
        return new BatchResponse(42L, "RCT-7K2Q", 5L, "M-0005", "Uwimana Claudine", "Big", 3L, "A-2026-01",
                LotStatus.CLOSED, Instant.parse("2026-10-19T08:00:00Z"), new BigDecimal("120.50"),
                new BigDecimal("100.00"), new BigDecimal("0.50"), new BigDecimal("20.00"), ConfirmationStatus.CONFIRMED,
                30000, 500, 29500, BatchPayment.BATCH);
    }
}
