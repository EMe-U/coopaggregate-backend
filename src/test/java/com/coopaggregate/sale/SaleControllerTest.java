package com.coopaggregate.sale;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.coopaggregate.buyer.BuyerResponse;
import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.payment.PaymentMethod;

import jakarta.persistence.EntityNotFoundException;

@WebMvcTest(SaleController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class SaleControllerTest {

    private static final String UUID_1 = "0f8fad5b-d9cb-469f-a165-70867728950e";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private SaleService saleService;

    @MockitoBean
    private AuthService authService;

    private String bearerToken;

    @BeforeEach
    void setUp() {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("manager@example.com").issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        bearerToken = "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        when(authService.currentManager("manager@example.com")).thenReturn(new Manager());
    }

    @Test
    void salesNeedALogin() throws Exception {
        mockMvc.perform(post("/api/sales").contentType(MediaType.APPLICATION_JSON).content(sale("")))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/sales/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(saleService);
    }

    @Test
    void oneSaleIsReturnedWithItsBatches() throws Exception {
        when(saleService.get(11L)).thenReturn(response());

        mockMvc.perform(get("/api/sales/11").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.saleCode").value("SAL-7K2Q"))
                .andExpect(jsonPath("$.buyerName").value("Kalisa Traders"))
                .andExpect(jsonPath("$.totalAmountRwf").value(30000))
                .andExpect(jsonPath("$.batches[0].memberName").value("Uwimana Claudine"))
                .andExpect(jsonPath("$.batches[0].netRwf").value(29500));
    }

    @Test
    void anUnknownSaleIs404AndANonNumericIdIs400() throws Exception {
        when(saleService.get(404L)).thenThrow(new EntityNotFoundException("Sale 404 does not exist."));

        mockMvc.perform(get("/api/sales/404").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Sale 404 does not exist."));
        mockMvc.perform(get("/api/sales/abc").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/sales/11")).andExpect(status().isUnauthorized());
    }

    @Test
    void aSaleOfAGradeIsCreatedWithItsBatches() throws Exception {
        when(saleService.record(any(), any())).thenReturn(new RecordedSale(response(), true));

        mockMvc.perform(post("/api/sales").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(sale("")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.gradeName").value("Big"))
                .andExpect(jsonPath("$.lotId").doesNotExist())
                .andExpect(jsonPath("$.totalNetRwf").value(29500))
                .andExpect(jsonPath("$.batches[0].receiptCode").value("RCT-0001"))
                .andExpect(jsonPath("$.batches[0].lotCode").value("A-2026-01"))
                .andExpect(jsonPath("$.batches[0].memberName").value("Uwimana Claudine"))
                .andExpect(jsonPath("$.batches[0].kg").value(100.00))
                .andExpect(jsonPath("$.batches[0].grossRwf").value(30000))
                .andExpect(jsonPath("$.batches[0].deductionRwf").value(500))
                .andExpect(jsonPath("$.batches[0].netRwf").value(29500));
    }

    @Test
    void theRequestCarriesTheGradeAndTheSkipFieldsReachTheServiceToBeRefused() throws Exception {
        when(saleService.record(any(), any())).thenThrow(new BadRequestException("Lots cannot be skipped: sales always use the oldest lot first."));

        mockMvc.perform(post("/api/sales").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sale(", \"skipLotIds\": [12, 15], \"skipReason\": \"Buyer wants fresher potatoes\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Lots cannot be skipped: sales always use the oldest lot first."));

        // The fields are still read, so a request that asks to skip is refused instead of being sold another way.
        ArgumentCaptor<SaleRequest> request = ArgumentCaptor.forClass(SaleRequest.class);
        verify(saleService).record(request.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals(new SaleRequest(UUID.fromString(UUID_1), 3L, 2L,
                new BigDecimal("100.00"), 300L, null, List.of(12L, 15L), "Buyer wants fresher potatoes", null, PaymentMethod.CASH, null),
                request.getValue());
    }

    @Test
    void aRepeatedSaleIsAnswered200() throws Exception {
        when(saleService.record(any(), any())).thenReturn(new RecordedSale(response(), false));

        mockMvc.perform(post("/api/sales").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(sale("")))
                .andExpect(status().isOk());
    }

    @Test
    void notEnoughKgIsA400WithTheMessage() throws Exception {
        when(saleService.record(any(), any())).thenThrow(new BadRequestException(
                "Only 250.00 kg of Big can be sold now, but 300.00 kg were asked for."));

        mockMvc.perform(post("/api/sales").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(sale("")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only 250.00 kg of Big can be sold now, but 300.00 kg were "
                        + "asked for."));
    }

    @Test
    void theOldBodyWithALotIdIsRefused() throws Exception {
        mockMvc.perform(post("/api/sales").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientUuid\": \"" + UUID_1 + "\", \"lotId\": 3, \"buyerId\": 2, "
                                + "\"quantityKg\": 100, \"pricePerKg\": 300, \"paymentMethod\": \"CASH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("gradeId: is required"));
        verifyNoInteractions(saleService);
    }

    @Test
    void quantityAndPriceAreChecked() throws Exception {
        mockMvc.perform(post("/api/sales").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientUuid\": \"" + UUID_1 + "\", \"gradeId\": 3, \"buyerId\": 2, "
                                + "\"quantityKg\": 0, \"pricePerKg\": 300}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/sales").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientUuid\": \"" + UUID_1 + "\", \"gradeId\": 3, \"buyerId\": 2, "
                                + "\"quantityKg\": 1.234, \"pricePerKg\": 300}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(saleService);
    }

    @Test
    void anOrderListsItsGrades() throws Exception {
        when(saleService.recordOrder(any(), any())).thenReturn(new RecordedSaleOrder(
                new SaleOrderResponse("ORD-7K2Q", new BuyerResponse(2L, "Kalisa Traders", "+250788123456", null, null),
                        LocalDate.of(2026, 10, 29), new BigDecimal("100.00"), 30_000L, PaymentMethod.MOMO,
                        "MP2610290001", List.of(response())), true));

        mockMvc.perform(post("/api/sales/orders").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientUuid\": \"" + UUID_1 + "\", \"buyerId\": 2, \"items\": ["
                                + "{\"gradeId\": 3, \"quantityKg\": 100, \"pricePerKg\": 300}, "
                                + "{\"gradeId\": 4, \"quantityKg\": 50.5, \"pricePerKg\": 200}], "
                                + "\"paymentMethod\": \"MOMO\", \"paymentReference\": \"MP2610290001\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderCode").value("ORD-7K2Q"))
                .andExpect(jsonPath("$.paymentMethod").value("MOMO"))
                .andExpect(jsonPath("$.paymentReference").value("MP2610290001"))
                .andExpect(jsonPath("$.sales[0].batches[0].netRwf").value(29500));

        ArgumentCaptor<SaleOrderRequest> request = ArgumentCaptor.forClass(SaleOrderRequest.class);
        verify(saleService).recordOrder(request.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals(List.of(
                new SaleOrderRequest.Item(3L, new BigDecimal("100"), 300L, null, null, null),
                new SaleOrderRequest.Item(4L, new BigDecimal("50.5"), 200L, null, null, null)), request.getValue().items());
        org.junit.jupiter.api.Assertions.assertEquals(PaymentMethod.MOMO, request.getValue().paymentMethod());
        org.junit.jupiter.api.Assertions.assertEquals("MP2610290001", request.getValue().paymentReference());
    }

    @Test
    void aSaleNeedsAKnownPaymentMethodAndAShortReference() throws Exception {
        String noMethod = "{\"clientUuid\": \"" + UUID_1 + "\", \"gradeId\": 3, \"buyerId\": 2, "
                + "\"quantityKg\": 100, \"pricePerKg\": 300}";
        mockMvc.perform(post("/api/sales").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(noMethod))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("paymentMethod: is required (CASH, MOMO or BANK)"));
        mockMvc.perform(post("/api/sales").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sale("").replace("\"CASH\"", "\"PAYPAL\"")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/sales").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sale(", \"paymentReference\": \"" + "x".repeat(51) + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("paymentReference: must be at most 50 characters"));
        verifyNoInteractions(saleService);
    }

    @Test
    void theMethodAndReferenceReachTheService() throws Exception {
        when(saleService.record(any(), any())).thenReturn(new RecordedSale(response(), true));

        mockMvc.perform(post("/api/sales").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sale("").replace("\"CASH\"", "\"BANK\", \"paymentReference\": \"BK-778812\"")))
                .andExpect(status().isCreated());

        ArgumentCaptor<SaleRequest> request = ArgumentCaptor.forClass(SaleRequest.class);
        verify(saleService).record(request.capture(), any());
        org.junit.jupiter.api.Assertions.assertEquals(PaymentMethod.BANK, request.getValue().paymentMethod());
        org.junit.jupiter.api.Assertions.assertEquals("BK-778812", request.getValue().paymentReference());
    }

    @Test
    void anOrderNeedsAPaymentMethod() throws Exception {
        mockMvc.perform(post("/api/sales/orders").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientUuid\": \"" + UUID_1 + "\", \"buyerId\": 2, \"items\": ["
                                + "{\"gradeId\": 3, \"quantityKg\": 100, \"pricePerKg\": 300}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("paymentMethod: is required (CASH, MOMO or BANK)"));
        verifyNoInteractions(saleService);
    }

    @Test
    void anItemWithALotIdOrWithoutAGradeIsRefused() throws Exception {
        mockMvc.perform(post("/api/sales/orders").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientUuid\": \"" + UUID_1 + "\", \"buyerId\": 2, \"items\": ["
                                + "{\"lotId\": 3, \"quantityKg\": 100, \"pricePerKg\": 300}], "
                                + "\"paymentMethod\": \"CASH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("items[0].gradeId: is required"));
        verifyNoInteractions(saleService);
    }

    private static String sale(String extra) {
        return "{\"clientUuid\": \"" + UUID_1 + "\", \"gradeId\": 3, \"buyerId\": 2, \"quantityKg\": 100.00, "
                + "\"pricePerKg\": 300, \"paymentMethod\": \"CASH\"" + extra + "}";
    }

    private static SaleResponse response() {
        return new SaleResponse(11L, "SAL-7K2Q", null, 3L, "Big", null, null, null, 2L, "Kalisa Traders",
                new BigDecimal("100.00"), 300L, 30_000L, false, LocalDate.of(2026, 10, 29),
                Instant.parse("2026-10-29T08:00:00Z"), 500L, 29_500L, List.of(
                        new SaleResponse.BatchLine(1L, "RCT-0001", 7L, "A-2026-01", 5L, "M-0005",
                                "Uwimana Claudine", new BigDecimal("100.00"), 30_000L, 500L, 29_500L)));
    }
}
