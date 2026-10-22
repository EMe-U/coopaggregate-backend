package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
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
import org.springframework.test.web.servlet.ResultActions;

import com.coopaggregate.auth.AuthService;
import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;

@WebMvcTest(IdentityController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class IdentityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private IdentityVerificationService verificationService;

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
    void callbackNeedsCodeAndState() throws Exception {
        callback("{\"code\": \"abc\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("state: is required"));
        verifyNoInteractions(verificationService);
    }

    @Test
    void duplicatePersonIs409WithoutDetails() throws Exception {
        when(verificationService.complete(any(), any(), any())).thenThrow(new DuplicateIdentityException());

        callback("{\"code\": \"abc\", \"state\": \"xyz\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This person is already a member."));
    }

    @Test
    void providerNotConfiguredIs503() throws Exception {
        when(verificationService.start(any())).thenThrow(
                IdentityProviderException.notConfigured("MOSIP verification is not configured yet."));

        mockMvc.perform(post("/api/members/verify/start").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("MOSIP verification is not configured yet."));
    }

    @Test
    void switchedOffTestVerificationIs503OnStartAndCallback() throws Exception {
        IdentityProviderException off = IdentityProviderException.notConfigured("Test verification is switched off.");
        when(verificationService.start(any())).thenThrow(off);
        when(verificationService.complete(any(), any(), any())).thenThrow(off);

        mockMvc.perform(post("/api/members/verify/start").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Test verification is switched off."));
        callback("{\"code\": \"abc\", \"state\": \"xyz\"}")
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Test verification is switched off."));
    }

    @Test
    void verificationNeedsALoggedInManager() throws Exception {
        mockMvc.perform(post("/api/members/verify/start")).andExpect(status().isUnauthorized());
    }

    @Test
    void codeAndStateAreMaskedWhenTheRequestIsPrinted() {
        assertEquals("VerifyCallbackRequest[code=****, state=****]",
                new VerifyCallbackRequest("the-code", "the-state").toString());
    }

    private ResultActions callback(String body) throws Exception {
        return mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
