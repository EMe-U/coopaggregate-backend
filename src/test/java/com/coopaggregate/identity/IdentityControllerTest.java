package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.manager.Manager;

@WebMvcTest(IdentityController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class IdentityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private IdentityAvailability identityAvailability;

    @MockitoBean
    private IdentityVerificationService verificationService;

    @MockitoBean
    private AuthService authService;

    private static final String CALLBACK_BODY = "{\"code\": \"c\", \"state\": \"s\"}";

    private String bearerToken;

    @BeforeEach
    void setUp() {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("manager@example.com").issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        bearerToken = "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    @Test
    void statusNeedsAToken() throws Exception {
        mockMvc.perform(get("/api/members/verify/status"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(identityAvailability);
    }

    @Test
    void statusSaysWhetherVerificationIsAvailableAndRequired() throws Exception {
        when(identityAvailability.status()).thenReturn(new IdentityStatusResponse(true, false));

        mockMvc.perform(get("/api/members/verify/status").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.required").value(false));
    }

    @Test
    void startReturnsWhereToSendTheMember() throws Exception {
        Manager manager = new Manager();
        when(authService.currentManager("manager@example.com")).thenReturn(manager);
        when(verificationService.start(manager)).thenReturn(new StartVerificationResponse("MOSIP",
                "http://localhost:8080/oauth2/authorize?x=y", "the-state", Instant.parse("2026-10-31T10:10:00Z")));

        mockMvc.perform(post("/api/members/verify/start").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("MOSIP"))
                .andExpect(jsonPath("$.authorizationUrl").value("http://localhost:8080/oauth2/authorize?x=y"))
                .andExpect(jsonPath("$.state").value("the-state"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-31T10:10:00Z"));
    }

    @Test
    void startAndCallbackNeedAToken() throws Exception {
        mockMvc.perform(post("/api/members/verify/start")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/members/verify/callback").contentType(MediaType.APPLICATION_JSON)
                .content(CALLBACK_BODY)).andExpect(status().isUnauthorized());

        verifyNoInteractions(verificationService);
    }

    @Test
    void callbackReturnsTheVerificationToRegisterTheMemberWith() throws Exception {
        Manager manager = new Manager();
        when(authService.currentManager("manager@example.com")).thenReturn(manager);
        when(verificationService.complete("the-code", "the-state", manager)).thenReturn(new VerifyResultResponse(9L,
                true, VerificationMethod.PASSWORD, Instant.parse("2026-10-31T10:30:00Z")));

        mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"the-code\", \"state\": \"the-state\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationId").value(9))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.method").value("PASSWORD"))
                .andExpect(jsonPath("$.useBefore").value("2026-10-31T10:30:00Z"))
                .andExpect(jsonPath("$.identityToken").doesNotExist())
                .andExpect(jsonPath("$.name").doesNotExist());
    }

    @Test
    void callbackNeedsACodeAndAState() throws Exception {
        mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"\", \"state\": \"s\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"c\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(verificationService);
    }

    @Test
    void aProviderThatIsNotAvailableIs503() throws Exception {
        when(authService.currentManager("manager@example.com")).thenReturn(new Manager());
        when(verificationService.start(any())).thenThrow(
                IdentityProviderException.notConfigured("Identity verification is not available."));

        mockMvc.perform(post("/api/members/verify/start").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Identity verification is not available."));
    }

    @Test
    void aProviderThatFailedIs502WithTheFixedMessage() throws Exception {
        when(authService.currentManager("manager@example.com")).thenReturn(new Manager());
        when(verificationService.complete(any(), any(), any())).thenThrow(
                IdentityProviderException.failed("The identity provider refused the token request."));

        mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(CALLBACK_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("The identity provider refused the token request."));
    }

    @Test
    void aPersonWhoIsAlreadyAMemberIs409() throws Exception {
        when(authService.currentManager("manager@example.com")).thenReturn(new Manager());
        when(verificationService.complete(any(), any(), any())).thenThrow(new DuplicateIdentityException());

        mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(CALLBACK_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This person is already a member."));
    }

    @Test
    void anUnknownOrExpiredStateIs400() throws Exception {
        when(authService.currentManager("manager@example.com")).thenReturn(new Manager());
        when(verificationService.complete(any(), any(), any())).thenThrow(
                new BadRequestException("Unknown verification. Start the verification again."));

        mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(CALLBACK_BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theCallbackRequestNeverPrintsTheCodeOrTheState() {
        assertFalse(new VerifyCallbackRequest("secret-code", "secret-state").toString().contains("secret"));
    }

    @Test
    void statusShowsARequirementThatCannotBeMet() throws Exception {
        when(identityAvailability.status()).thenReturn(new IdentityStatusResponse(false, true));

        mockMvc.perform(get("/api/members/verify/status").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.required").value(true));
    }
}
