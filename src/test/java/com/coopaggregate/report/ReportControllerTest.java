package com.coopaggregate.report;

import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;
import com.coopaggregate.error.BadRequestException;

@WebMvcTest(ReportController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class ReportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private ReportService reportService;

    private String bearerToken;

    @BeforeEach
    void setUp() {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("manager@example.com").issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        bearerToken = "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void reportNeedsALogin() throws Exception {
        mockMvc.perform(get("/api/reports/weekly")).andExpect(status().isUnauthorized());
        verifyNoInteractions(reportService);
    }

    @Test
    void weekStartIsPassedAsADate() throws Exception {
        when(reportService.weekly(LocalDate.of(2026, 10, 19))).thenReturn(emptyReport());

        mockMvc.perform(get("/api/reports/weekly").param("weekStart", "2026-10-19")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period.weekStart").value("2026-10-19"))
                .andExpect(jsonPath("$.period.weekEnd").value("2026-10-25"))
                .andExpect(jsonPath("$.deliveries.totalKg").value(0))
                .andExpect(jsonPath("$.money.owedNowRwf").value(0))
                .andExpect(jsonPath("$.confirmations.disputedNow").value(0));
    }

    @Test
    void withoutWeekStartTheServiceChoosesTheCurrentWeek() throws Exception {
        when(reportService.weekly(null)).thenReturn(emptyReport());

        mockMvc.perform(get("/api/reports/weekly").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk());

        verify(reportService).weekly(isNull());
    }

    @Test
    void notAMondayIs400() throws Exception {
        when(reportService.weekly(LocalDate.of(2026, 10, 21)))
                .thenThrow(new BadRequestException("weekStart must be a Monday, for example 2026-10-19."));

        mockMvc.perform(get("/api/reports/weekly").param("weekStart", "2026-10-21")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("weekStart must be a Monday, for example 2026-10-19."));
    }

    @Test
    void invalidDateIs400() throws Exception {
        mockMvc.perform(get("/api/reports/weekly").param("weekStart", "19-10-2026")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reportService);
    }

    private static WeeklyReport emptyReport() {
        return new WeeklyReport(
                new WeeklyReport.Period(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)),
                new WeeklyReport.Deliveries(BigDecimal.ZERO, 0, List.of(), List.of()),
                new WeeklyReport.Stock(BigDecimal.ZERO, List.of(), BigDecimal.ZERO, List.of()),
                new WeeklyReport.Sales(List.of(), BigDecimal.ZERO, 0, BigDecimal.ZERO),
                new WeeklyReport.Money(0, 0, 0, 0, List.of(), 0),
                new WeeklyReport.Confirmations(0, List.of(), 0, 0, 0, 0));
    }
}
