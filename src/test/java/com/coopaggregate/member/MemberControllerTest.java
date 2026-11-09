package com.coopaggregate.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.boot.test.system.CapturedOutput;
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
import com.coopaggregate.identity.IdentityErrorCode;
import com.coopaggregate.identity.IdentityProviderException;
import com.coopaggregate.identity.IdentityRequestException;
import com.coopaggregate.identity.VerificationMethod;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.EntityNotFoundException;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
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
                MemberStatus.ACTIVE, "rw", Instant.parse("2026-10-08T10:00:00Z"), false, null, null, null, null, null,
                List.of(), null)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/members").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].fullName").value("Uwimana Claudine"))
                .andExpect(jsonPath("$.content[0].nationalId").doesNotExist());
    }

    @Test
    void theListShowsTheMaskedNationalIdAndNeverTheFullOne(CapturedOutput output) throws Exception {
        Member withId = listMember("M-0001", "1199880012345678");
        Member old = listMember("M-0002", null);
        when(memberService.list(null, null, 0, 20)).thenReturn(new PageResponse<>(
                List.of(MemberListItem.from(withId), MemberListItem.from(old)), 0, 20, 2, 1));

        String body = mockMvc.perform(get("/api/members").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].nationalIdMasked").value("••••••••••••5678"))
                .andExpect(jsonPath("$.content[0].nationalId").doesNotExist())
                .andExpect(jsonPath("$.content[1].memberCode").value("M-0002"))
                .andExpect(jsonPath("$.content[1].nationalIdMasked").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(body.contains("1199880012345678"), body);
        assertFalse(body.contains("119988001234"), body);
        assertFalse(output.getAll().contains("1199880012345678"));
    }

    private static Member listMember(String memberCode, String nationalId) {
        Member member = new Member();
        member.setMemberCode(memberCode);
        member.setFullName("Uwimana Claudine");
        member.setPhone("+250788123456");
        member.setNationalId(nationalId);
        member.setJoinDate(LocalDate.of(2026, 10, 8));
        return member;
    }

    @Test
    void theListShowsTheVerifiedDetailsAndWhichFieldsWereVerified() throws Exception {
        when(memberService.list(null, null, 0, 20)).thenReturn(new PageResponse<>(List.of(new MemberListItem(
                1L, "M-0001", "Uwimana Claudine", "+250788123456", "Kacyiru, Gasabo, Kigali, Rwanda",
                LocalDate.of(2026, 10, 8), MemberStatus.ACTIVE, "rw", Instant.parse("2026-10-08T10:00:00Z"), true,
                VerificationMethod.PASSWORD, Instant.parse("2026-10-08T09:55:00Z"), LocalDate.of(1990, 5, 17),
                "Female", "claudine@example.com", List.of("fullName", "phone", "birthdate", "gender", "email",
                "address"), null)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/members").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].birthDate").value("1990-05-17"))
                .andExpect(jsonPath("$.content[0].gender").value("Female"))
                .andExpect(jsonPath("$.content[0].email").value("claudine@example.com"))
                .andExpect(jsonPath("$.content[0].address").value("Kacyiru, Gasabo, Kigali, Rwanda"))
                .andExpect(jsonPath("$.content[0].verifiedFields[2]").value("birthdate"))
                .andExpect(jsonPath("$.content[0].verifiedFields.length()").value(6));
    }

    @Test
    void oneMemberShowsTheVerifiedDetailsWithNullForTheMissingOnes() throws Exception {
        when(memberService.get(1L)).thenReturn(new MemberResponse(
                1L, "M-0001", "Uwimana Claudine", "+250788123456", null, "Busogo",
                LocalDate.of(2026, 10, 8), MemberStatus.ACTIVE, "rw", Instant.parse("2026-10-08T10:00:00Z"), true,
                VerificationMethod.PASSWORD, Instant.parse("2026-10-08T09:55:00Z"), null, "Female", null,
                List.of("fullName", "phone", "gender")));

        mockMvc.perform(get("/api/members/1").header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.birthDate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.gender").value("Female"))
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.address").value("Busogo"))
                .andExpect(jsonPath("$.verifiedFields").value(org.hamcrest.Matchers.contains("fullName", "phone",
                        "gender")));
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
                VerificationMethod.PASSWORD, Instant.parse("2026-10-08T09:55:00Z"), null, null, null,
                List.of("fullName", "phone")));

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
    void aLockedFieldSentWithTheRegistrationIs400WithItsCode() throws Exception {
        when(memberService.create(any(NewMemberRequest.class), any())).thenThrow(new IdentityRequestException(
                IdentityErrorCode.VERIFIED_FIELD_LOCKED,
                "This detail came from the identity check and cannot be changed here."));

        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId": 9, "fullName": "Someone Else", "phone": "0788999999"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VERIFIED_FIELD_LOCKED"))
                .andExpect(jsonPath("$.message")
                        .value("This detail came from the identity check and cannot be changed here."));
    }

    @Test
    void everyLockableFieldOfTheBodyReachesTheCheck() throws Exception {
        Manager manager = new Manager();
        when(authService.currentManager("manager@example.com")).thenReturn(manager);

        mockMvc.perform(post("/api/members")
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"verificationId": 9, "fullName": "", "phone": "0788999999", "birthdate": "1990-05-17",
                         "gender": "Male", "email": "x@example.com", "address": "Musanze"}
                        """));

        ArgumentCaptor<NewMemberRequest> request = ArgumentCaptor.forClass(NewMemberRequest.class);
        verify(memberService).create(request.capture(), eq(manager));
        assertEquals("", request.getValue().fullName());
        assertEquals("0788999999", request.getValue().phone());
        assertEquals(LocalDate.of(1990, 5, 17), request.getValue().birthDate());
        assertEquals("Male", request.getValue().gender());
        assertEquals("x@example.com", request.getValue().email());
        assertEquals("Musanze", request.getValue().address());
    }

    @Test
    void theBirthDateIsAlsoReadWithItsMemberResponseName() throws Exception {
        Manager manager = new Manager();
        when(authService.currentManager("manager@example.com")).thenReturn(manager);

        mockMvc.perform(post("/api/members")
                .header(HttpHeaders.AUTHORIZATION, bearerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"verificationId": 9, "birthDate": "1990-05-17"}
                        """));

        ArgumentCaptor<NewMemberRequest> request = ArgumentCaptor.forClass(NewMemberRequest.class);
        verify(memberService).create(request.capture(), eq(manager));
        assertEquals(LocalDate.of(1990, 5, 17), request.getValue().birthDate());
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
    void aMissingNationalIdReturns400WithItsCode() throws Exception {
        when(memberService.create(any(NewMemberRequest.class), any())).thenThrow(
                new NationalIdException(NationalIdErrorCode.NATIONAL_ID_REQUIRED, "nationalId: is required"));

        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId": 9}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().json(
                        "{\"message\":\"nationalId: is required\",\"code\":\"NATIONAL_ID_REQUIRED\"}", true));
    }

    @Test
    void aShortNationalIdReachesTheServiceAndReturns400WithItsCode() throws Exception {
        when(memberService.create(any(NewMemberRequest.class), any())).thenThrow(
                new NationalIdException(NationalIdErrorCode.NATIONAL_ID_INVALID, "nationalId: must be exactly 16 digits"));

        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId": 9, "nationalId": "12345"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NATIONAL_ID_INVALID"))
                .andExpect(jsonPath("$.message").value("nationalId: must be exactly 16 digits"));
    }

    @Test
    void aTakenNationalIdReturns409WithTheOtherMembersCodeOnly() throws Exception {
        when(memberService.create(any(NewMemberRequest.class), any())).thenThrow(new NationalIdTakenException("M-0004"));

        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"verificationId": 9, "nationalId": "1199880012345678"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().json("{\"message\":\"This national ID is already used by member M-0004.\","
                        + "\"code\":\"NATIONAL_ID_TAKEN\",\"memberCode\":\"M-0004\"}", true));
    }

    @Test
    void anEditThatWouldRemoveTheNationalIdReturns400() throws Exception {
        when(memberService.update(eq(5L), any(MemberRequest.class))).thenThrow(new NationalIdException(
                NationalIdErrorCode.NATIONAL_ID_REQUIRED, "nationalId: cannot be removed once it is set"));

        mockMvc.perform(put("/api/members/5")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Uwimana Claudine", "phone": "0788123456", "nationalId": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NATIONAL_ID_REQUIRED"));
    }

    @Test
    void aSearchByNationalIdNeverShowsOrLogsTheId(CapturedOutput output) throws Exception {
        Member member = new Member();
        member.setMemberCode("M-0001");
        member.setFullName("Uwimana Claudine");
        member.setPhone("+250788123456");
        member.setNationalId("1199880012345678");
        member.setJoinDate(LocalDate.of(2026, 10, 8));
        when(memberService.list("1199880012345678", null, 0, 20))
                .thenReturn(new PageResponse<>(List.of(MemberListItem.from(member)), 0, 20, 1, 1));

        String body = mockMvc.perform(get("/api/members").param("search", "1199880012345678")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].memberCode").value("M-0001"))
                .andExpect(jsonPath("$.content[0].nationalId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains("1199880012345678"), body);
        assertFalse(MemberListItem.from(member).toString().contains("1199880012345678"));
        assertFalse(output.getAll().contains("1199880012345678"));
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
                .andExpect(jsonPath("$.message").value("Phone number +250788123456 is already used by member M-0003."))
                .andExpect(jsonPath("$.code").doesNotExist());
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
