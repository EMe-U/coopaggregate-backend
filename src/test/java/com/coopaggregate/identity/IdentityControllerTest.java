package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
        when(identityAvailability.status()).thenReturn(new IdentityStatusResponse(true, false, false));

        mockMvc.perform(get("/api/members/verify/status").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.required").value(false))
                .andExpect(jsonPath("$.unverifiedAllowed").value(false));
    }

    @Test
    void statusSaysWhenAMemberMayBeRegisteredWithoutVerification() throws Exception {
        when(identityAvailability.status()).thenReturn(new IdentityStatusResponse(false, false, true));

        mockMvc.perform(get("/api/members/verify/status").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"available\":false,\"required\":false,\"unverifiedAllowed\":true}", true));
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
        when(verificationService.complete("the-code", "the-state", manager)).thenReturn(VerifyResultResponse.of(9L,
                VerificationMethod.PASSWORD, new VerifiedDetails("UWIMANA Claudine", "+250788123456"),
                Instant.parse("2026-10-31T10:30:00Z")));

        mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"the-code\", \"state\": \"the-state\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationId").value(9))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.method").value("PASSWORD"))
                .andExpect(jsonPath("$.useBefore").value("2026-10-31T10:30:00Z"))
                .andExpect(jsonPath("$.fullName").value("UWIMANA Claudine"))
                .andExpect(jsonPath("$.phone").value("+250788123456"))
                .andExpect(jsonPath("$.identityToken").doesNotExist())
                .andExpect(jsonPath("$.birthdate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.gender").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.address").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.verifiedFields").value(org.hamcrest.Matchers.contains("fullName", "phone")));
    }

    @Test
    void theCallbackReturnsEveryVerifiedDetailInItsFormat() throws Exception {
        Manager manager = new Manager();
        when(authService.currentManager("manager@example.com")).thenReturn(manager);
        when(verificationService.complete("the-code", "the-state", manager)).thenReturn(VerifyResultResponse.of(9L,
                VerificationMethod.PASSWORD, new VerifiedDetails("UWIMANA Claudine", "+250788123456",
                        java.time.LocalDate.of(1990, 5, 17), "Female", "claudine@example.com",
                        "Kacyiru, Gasabo, Kigali, Rwanda"), Instant.parse("2026-10-31T10:30:00Z")));

        mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"the-code\", \"state\": \"the-state\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.birthdate").value("1990-05-17"))
                .andExpect(jsonPath("$.gender").value("Female"))
                .andExpect(jsonPath("$.email").value("claudine@example.com"))
                .andExpect(jsonPath("$.address").value("Kacyiru, Gasabo, Kigali, Rwanda"))
                .andExpect(jsonPath("$.verifiedFields").value(org.hamcrest.Matchers.contains("fullName", "phone",
                        "birthdate", "gender", "email", "address")))
                .andExpect(jsonPath("$.picture").doesNotExist());
    }

    @Test
    void theCallbackResultNeverPrintsTheVerifiedDetails() {
        String text = VerifyResultResponse.of(9L, VerificationMethod.PASSWORD, new VerifiedDetails("UWIMANA Claudine",
                "+250788123456", java.time.LocalDate.of(1990, 5, 17), "Female", "claudine@example.com", "Kacyiru"),
                Instant.parse("2026-10-31T10:30:00Z")).toString();

        for (String value : java.util.List.of("UWIMANA", "788123456", "1990", "Female", "claudine", "Kacyiru")) {
            assertFalse(text.contains(value), value);
        }
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
                .andExpect(jsonPath("$.message").value("Identity verification is not available."))
                .andExpect(jsonPath("$.code").value("NOT_CONFIGURED"));
    }

    @Test
    void aProviderThatFailedIs502WithTheFixedMessage() throws Exception {
        when(authService.currentManager("manager@example.com")).thenReturn(new Manager());
        when(verificationService.complete(any(), any(), any())).thenThrow(
                IdentityProviderException.failed("The identity provider refused the token request."));

        mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(CALLBACK_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("The identity provider refused the token request."))
                .andExpect(jsonPath("$.code").value("PROVIDER_FAILED"));
    }

    @Test
    void aPersonWhoIsAlreadyAMemberIs409() throws Exception {
        when(authService.currentManager("manager@example.com")).thenReturn(new Manager());
        when(verificationService.complete(any(), any(), any())).thenThrow(new DuplicateIdentityException());

        mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(CALLBACK_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This person is already a member."))
                .andExpect(jsonPath("$.code").value("ALREADY_MEMBER"));
    }

    @Test
    void anUnknownOrExpiredStateIs400() throws Exception {
        when(authService.currentManager("manager@example.com")).thenReturn(new Manager());
        when(verificationService.complete(any(), any(), any())).thenThrow(new IdentityRequestException(
                IdentityErrorCode.VERIFICATION_UNKNOWN, "Unknown verification. Start the verification again."));

        mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(CALLBACK_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFICATION_UNKNOWN"));
    }

    @Test
    void eachRefusedCallbackCarriesItsCode() throws Exception {
        when(authService.currentManager("manager@example.com")).thenReturn(new Manager());
        String[][] cases = {
                {"VERIFICATION_EXPIRED", "400"}, {"CLAIMS_MISSING", "400"}, {"PHONE_NOT_RWANDAN", "400"},
                {"NAME_TOO_LONG", "400"}, {"VERIFICATION_USED", "409"}};
        for (String[] c : cases) {
            IdentityErrorCode code = IdentityErrorCode.valueOf(c[0]);
            RuntimeException error = c[1].equals("409") ? new IdentityConflictException(code, "The message.")
                    : new IdentityRequestException(code, "The message.");
            org.mockito.Mockito.doThrow(error).when(verificationService).complete(any(), any(), any());

            mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                            .contentType(MediaType.APPLICATION_JSON).content(CALLBACK_BODY))
                    .andExpect(status().is(Integer.parseInt(c[1])))
                    .andExpect(jsonPath("$.message").value("The message."))
                    .andExpect(jsonPath("$.code").value(c[0]));
        }
    }

    @Test
    void otherErrorsHaveNoCode() throws Exception {
        when(authService.currentManager("manager@example.com")).thenReturn(new Manager());
        when(verificationService.complete(any(), any(), any())).thenThrow(new BadRequestException("Plain."));

        mockMvc.perform(post("/api/members/verify/callback").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(CALLBACK_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Plain."))
                .andExpect(jsonPath("$.code").doesNotExist());
    }

    // --- Coming back from eSignet with an error

    @Test
    void cancelReportsTheErrorAndReturnsTheReasonAndMessage() throws Exception {
        Manager manager = new Manager();
        when(authService.currentManager("manager@example.com")).thenReturn(manager);
        when(verificationService.cancel("the-state", "essential_consent_denied", manager))
                .thenReturn(CancelResponse.of(CancelResponse.Reason.CLAIMS_REFUSED));

        mockMvc.perform(post("/api/members/verify/cancel").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"the-state\", \"error\": \"essential_consent_denied\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("CLAIMS_REFUSED"))
                .andExpect(jsonPath("$.message").value("The member did not allow eSignet to share their name and "
                        + "phone number, which are needed to register them. Nothing was saved."));
    }

    @Test
    void cancelNeedsATokenAndAState() throws Exception {
        mockMvc.perform(post("/api/members/verify/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\": \"s\", \"error\": \"access_denied\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/members/verify/cancel").header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"error\": \"access_denied\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(verificationService);
    }

    @Test
    void theCancelRequestNeverPrintsTheState() {
        assertFalse(new VerifyCancelRequest("secret-state", "access_denied").toString().contains("secret"));
    }

    @Test
    void theCallbackRequestNeverPrintsTheCodeOrTheState() {
        assertFalse(new VerifyCallbackRequest("secret-code", "secret-state").toString().contains("secret"));
    }

    @Test
    void statusShowsARequirementThatCannotBeMet() throws Exception {
        when(identityAvailability.status()).thenReturn(new IdentityStatusResponse(false, true, false));

        mockMvc.perform(get("/api/members/verify/status").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.required").value(true));
    }
}
