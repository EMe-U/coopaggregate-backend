package com.coopaggregate.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

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
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;
import com.coopaggregate.identity.DuplicateIdentityException;
import com.coopaggregate.identity.IdentityProviderException;
import com.coopaggregate.identity.VerificationMethod;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.EntityNotFoundException;

@WebMvcTest(MemberController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private MemberService memberService;

    @MockitoBean
    private AuthService authService;

    private String bearerToken;

    @BeforeEach
    void setUp() {
        // A real token signed with the test secret, so requests pass the same JWT check as in production.
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("manager@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        bearerToken = "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/members"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(memberService);
    }

    @Test
    void listDoesNotShowTheNationalId() throws Exception {
        when(memberService.list(null, null, 0, 20)).thenReturn(new PageResponse<>(List.of(new MemberListItem(
                1L, "M-0001", "Uwimana Claudine", "+250788123456", null, LocalDate.of(2026, 10, 8),
                MemberStatus.ACTIVE, "rw", Instant.parse("2026-10-08T10:00:00Z"), false, null, null)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/members").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].fullName").value("Uwimana Claudine"))
                .andExpect(jsonPath("$.content[0].nationalId").doesNotExist());
    }

    @Test
    void summaryReturnsCounts() throws Exception {
        when(memberService.summary()).thenReturn(new MemberSummaryResponse(12, 10, 2, 3));

        mockMvc.perform(get("/api/members/summary").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(12))
                .andExpect(jsonPath("$.active").value(10))
                .andExpect(jsonPath("$.inactive").value(2))
                .andExpect(jsonPath("$.joinedThisMonth").value(3));
    }

    @Test
    void summaryWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/members/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void requestWithInvalidTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/members").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(memberService);
    }

    @Test
    void createReturns201WithTheMemberRegisteredByTheLoggedInManager() throws Exception {
        Manager manager = new Manager();
        when(authService.currentManager("manager@example.com")).thenReturn(manager);
        when(memberService.create(any(NewMemberRequest.class), eq(manager))).thenReturn(new MemberResponse(
                1L, "M-0001", "Uwimana Claudine", "+250788123456", null, "Busogo",
                LocalDate.of(2026, 10, 8), MemberStatus.ACTIVE, "rw", Instant.parse("2026-10-08T10:00:00Z"), true,
                VerificationMethod.PASSWORD, Instant.parse("2026-10-08T09:55:00Z")));

        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId": 9, "address": "Busogo", "preferredLanguage": "rw"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberCode").value("M-0001"))
                .andExpect(jsonPath("$.fullName").value("Uwimana Claudine"))
                .andExpect(jsonPath("$.phone").value("+250788123456"))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.verificationMethod").value("PASSWORD"))
                .andExpect(jsonPath("$.identityToken").doesNotExist());

        ArgumentCaptor<NewMemberRequest> request = ArgumentCaptor.forClass(NewMemberRequest.class);
        verify(memberService).create(request.capture(), eq(manager));
        assertEquals(9L, request.getValue().verificationId());
        assertEquals("Busogo", request.getValue().address());
    }

    @Test
    void aNameOrPhoneSentWithTheRegistrationIs400() throws Exception {
        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId": 9, "fullName": "Someone Else", "phone": "0788999999"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("fullName: must not be sent: the name comes from the identity "
                        + "verification; phone: must not be sent: the phone number comes from the identity "
                        + "verification"));

        verifyNoInteractions(memberService);
    }

    @Test
    void anEmptyNameIsStillRefused() throws Exception {
        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId": 9, "fullName": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("fullName: must not be sent: the name comes from the identity "
                        + "verification"));

        verifyNoInteractions(memberService);
    }

    @Test
    void anOldBirthDateFieldIsIgnored() throws Exception {
        when(memberService.create(any(NewMemberRequest.class), any())).thenReturn(new MemberResponse(
                1L, "M-0001", "Uwimana Claudine", "+250788123456", null, null,
                LocalDate.of(2026, 10, 8), MemberStatus.ACTIVE, "rw", Instant.parse("2026-10-08T10:00:00Z"), true,
                VerificationMethod.PASSWORD, Instant.parse("2026-10-08T09:55:00Z")));

        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId": 9, "birthDate": "1990-05-17"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.birthDate").doesNotExist());
    }

    @Test
    void aPersonWhoIsAlreadyAMemberIs409() throws Exception {
        when(memberService.create(any(NewMemberRequest.class), any())).thenThrow(new DuplicateIdentityException());

        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId": 9}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This person is already a member."));
    }

    @Test
    void registrationWithoutIdentityVerificationConfiguredIs503WithAClearMessage() throws Exception {
        when(memberService.create(any(NewMemberRequest.class), any())).thenThrow(IdentityProviderException.notConfigured(
                "Member registration needs identity verification, which is not configured."));

        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId": 9}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Member registration needs identity verification, which is not configured."));
    }

    @Test
    void createWithShortNationalIdReturns400() throws Exception {
        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId": 9, "nationalId": "12345"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("nationalId: must be 16 digits"));
    }

    @Test
    void createWithDuplicatePhoneReturns409() throws Exception {
        when(memberService.create(any(NewMemberRequest.class), any())).thenThrow(
                new IllegalStateException("Phone number +250788123456 is already used by member M-0003."));

        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId": 9}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Phone number +250788123456 is already used by member M-0003."));
    }

    @Test
    void anUpdateWithMissingNameAndInvalidPhoneReturns400() throws Exception {
        mockMvc.perform(put("/api/members/5")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "", "phone": "12345"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("fullName: is required; "
                        + "phone: must be a Rwandan mobile number: 07XXXXXXXX, 2507XXXXXXXX or +2507XXXXXXXX"));

        verifyNoInteractions(memberService);
    }

    @Test
    void deactivateUnknownMemberReturns404() throws Exception {
        when(memberService.setActive(eq(99L), eq(false)))
                .thenThrow(new EntityNotFoundException("Member 99 does not exist."));

        mockMvc.perform(patch("/api/members/99/deactivate").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Member 99 does not exist."));
    }
}
