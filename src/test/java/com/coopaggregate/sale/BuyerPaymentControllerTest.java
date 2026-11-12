package com.coopaggregate.sale;

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

import com.coopaggregate.common.PageResponse;
import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;
import com.coopaggregate.payment.PaymentMethod;

import jakarta.persistence.EntityNotFoundException;

@WebMvcTest(BuyerPaymentController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class BuyerPaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private BuyerPaymentService buyerPaymentService;

    private String bearerToken;

    @BeforeEach
    void setUp() {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("manager@example.com").issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        bearerToken = "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void buyerPaymentsNeedALogin() throws Exception {
        mockMvc.perform(get("/api/buyer-payments")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/buyer-payments/10")).andExpect(status().isUnauthorized());
        verifyNoInteractions(buyerPaymentService);
    }

    @Test
    void theListShowsEachOrderWithItsBuyerMethodTotalAndFarmers() throws Exception {
        when(buyerPaymentService.list(2L, 1, 10)).thenReturn(new PageResponse<>(List.of(payment()), 1, 10, 11, 2));

        mockMvc.perform(get("/api/buyer-payments?buyerId=2&page=1&size=10")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].orderCode").value("ORD-7K2Q"))
                .andExpect(jsonPath("$.content[0].buyerName").value("Kalisa Traders"))
                .andExpect(jsonPath("$.content[0].method").value("MOMO"))
                .andExpect(jsonPath("$.content[0].reference").value("MP2611100001"))
                .andExpect(jsonPath("$.content[0].totalAmountRwf").value(56000))
                .andExpect(jsonPath("$.content[0].sales[1].gradeName").value("Small"))
                .andExpect(jsonPath("$.content[0].farmers[1].memberName").value("Uwimana Claudine"))
                .andExpect(jsonPath("$.content[0].farmers[1].kg").value(170.00))
                .andExpect(jsonPath("$.content[0].farmers[1].deductionRwf").value(850))
                .andExpect(jsonPath("$.content[0].farmers[1].netRwf").value(40150))
                .andExpect(jsonPath("$.totalElements").value(11));
    }

    @Test
    void theListDefaultsToTheFirstPageOf20ForAllBuyers() throws Exception {
        when(buyerPaymentService.list(null, 0, 20)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/buyer-payments").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk());

        verify(buyerPaymentService).list(null, 0, 20);
    }

    @Test
    void oneBuyerPaymentIsReturnedAndAnUnknownOneIs404() throws Exception {
        when(buyerPaymentService.get(10L)).thenReturn(payment());
        when(buyerPaymentService.get(404L)).thenThrow(new EntityNotFoundException("Buyer payment 404 does not exist."));

        mockMvc.perform(get("/api/buyer-payments/10").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(10))
                .andExpect(jsonPath("$.farmers[0].memberNumber").value("M-0006"));
        mockMvc.perform(get("/api/buyer-payments/404").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Buyer payment 404 does not exist."));
    }

    @Test
    void anOldOrderHasNoMethod() throws Exception {
        BuyerPaymentResponse old = new BuyerPaymentResponse(3L, "ORD-S-3", 2L, "Kalisa Traders",
                LocalDate.of(2026, 9, 1), Instant.parse("2026-09-01T08:00:00Z"), null, null,
                new BigDecimal("40.00"), 10_000L, null, null, List.of(), List.of());
        when(buyerPaymentService.get(3L)).thenReturn(old);

        mockMvc.perform(get("/api/buyer-payments/3").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").isEmpty())
                .andExpect(jsonPath("$.totalNetRwf").isEmpty());
    }

    private static BuyerPaymentResponse payment() {
        return new BuyerPaymentResponse(10L, "ORD-7K2Q", 2L, "Kalisa Traders", LocalDate.of(2026, 11, 10),
                Instant.parse("2026-11-10T08:00:00Z"), PaymentMethod.MOMO, "MP2611100001",
                new BigDecimal("220.00"), 56_000L, 1_100L, 54_900L,
                List.of(new BuyerPaymentResponse.SaleLine(11L, "SAL-AAAA", 1L, "Big", new BigDecimal("120.00"), 300L,
                                36_000L),
                        new BuyerPaymentResponse.SaleLine(12L, "SAL-BBBB", 2L, "Small", new BigDecimal("100.00"), 200L,
                                20_000L)),
                List.of(new BuyerPaymentResponse.FarmerShare(6L, "M-0006", "Habimana Bosco", new BigDecimal("50.00"),
                                15_000L, 250L, 14_750L),
                        new BuyerPaymentResponse.FarmerShare(5L, "M-0005", "Uwimana Claudine",
                                new BigDecimal("170.00"), 41_000L, 850L, 40_150L)));
    }
}
