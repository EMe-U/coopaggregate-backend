package com.coopaggregate.buyer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;
import com.coopaggregate.manager.Manager;

@WebMvcTest(BuyerRequestController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class BuyerRequestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private BuyerRequestService requestService;

    @MockitoBean
    private AuthService authService;

    private String bearerToken;
    private final Manager manager = new Manager();

    @BeforeEach
    void setUp() {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("manager@example.com").issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        bearerToken = "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        when(authService.currentManager("manager@example.com")).thenReturn(manager);
    }

    @Test
    void everyEndpointNeedsALogin() throws Exception {
        mockMvc.perform(get("/api/buyer-requests")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/buyer-requests/5")).andExpect(status().isUnauthorized());
        mockMvc.perform(patch("/api/buyer-requests/5/status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"ACCEPTED\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/buyer-requests/5/buyer")).andExpect(status().isUnauthorized());
        verifyNoInteractions(requestService);
    }

    @Test
    void listUsesFifteenPerPageAndTheFilters() throws Exception {
        when(requestService.list(BuyerRequestStatus.PENDING, "kalisa", 0, 15)).thenReturn(new PageResponse<>(
                List.of(new BuyerRequestListItem(5L, "BR-2026-7K2Q", BuyerRequestStatus.PENDING, "Big",
                        new BigDecimal("250.50"), "Kalisa Traders", "+250788123456", false,
                        Instant.parse("2026-10-22T08:00:00Z"))), 0, 15, 1, 1));

        mockMvc.perform(get("/api/buyer-requests").param("status", "PENDING").param("search", "kalisa")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].code").value("BR-2026-7K2Q"))
                .andExpect(jsonPath("$.content[0].buyerName").value("Kalisa Traders"))
                .andExpect(jsonPath("$.content[0].converted").value(false))
                .andExpect(jsonPath("$.size").value(15));
    }

    @Test
    void unknownStatusFilterIs400() throws Exception {
        mockMvc.perform(get("/api/buyer-requests").param("status", "MAYBE")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(requestService);
    }

    @Test
    void statusChangeNeedsAStatus() throws Exception {
        mockMvc.perform(patch("/api/buyer-requests/5/status").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"publicMessage\": \"Hello\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("status: is required (PENDING, ACCEPTED or REJECTED)"));
        verifyNoInteractions(requestService);
    }

    @Test
    void publicMessageIsLimitedTo500Characters() throws Exception {
        mockMvc.perform(patch("/api/buyer-requests/5/status").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACCEPTED\", \"publicMessage\": \"" + "x".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("publicMessage: must be at most 500 characters"));
    }

    @Test
    void statusChangeReturnsTheUpdatedRequest() throws Exception {
        when(requestService.changeStatus(eq(5L), any(BuyerRequestStatusUpdate.class), eq(manager))).thenReturn(
                new BuyerRequestDetail(5L, "BR-2026-7K2Q", BuyerRequestStatus.ACCEPTED, "A", "Big",
                        new BigDecimal("250.50"), "Kalisa Traders", "+250788123456", "kalisa@example.com",
                        "Friday", "We will call you.", null, Instant.parse("2026-10-22T08:00:00Z"),
                        Instant.parse("2026-10-22T09:00:00Z")));

        mockMvc.perform(patch("/api/buyer-requests/5/status").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"ACCEPTED\", \"publicMessage\": \"We will call you.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.publicMessage").value("We will call you."));
        verify(requestService).changeStatus(eq(5L), eq(new BuyerRequestStatusUpdate(BuyerRequestStatus.ACCEPTED,
                "We will call you.")), eq(manager));
    }

    @Test
    void conversionReturnsTheRequestWithItsBuyer() throws Exception {
        when(requestService.convertToBuyer(5L, manager)).thenReturn(
                new BuyerRequestDetail(5L, "BR-2026-7K2Q", BuyerRequestStatus.ACCEPTED, "A", "Big",
                        new BigDecimal("250.50"), "Kalisa Traders", "+250788123456", null, null, null, 12L,
                        Instant.parse("2026-10-22T08:00:00Z"), null));

        mockMvc.perform(post("/api/buyer-requests/5/buyer").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyerId").value(12));
    }

    @Test
    void alreadyConvertedRequestIs409() throws Exception {
        when(requestService.convertToBuyer(5L, manager))
                .thenThrow(new IllegalStateException("Request BR-2026-7K2Q already has a buyer."));

        mockMvc.perform(post("/api/buyer-requests/5/buyer").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Request BR-2026-7K2Q already has a buyer."));
    }
}
