package com.coopaggregate.member;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
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

import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;

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
    void createReturns201WithMember() throws Exception {
        when(memberService.create(any(MemberRequest.class))).thenReturn(new MemberResponse(
                1L, "MEM-0001", "Uwimana Claudine", "+250788123456", null, null,
                LocalDate.of(2026, 10, 8), MemberStatus.ACTIVE, "rw", Instant.parse("2026-10-08T10:00:00Z")));

        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Uwimana Claudine", "phone": "0788123456"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberCode").value("MEM-0001"))
                .andExpect(jsonPath("$.phone").value("+250788123456"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void createWithMissingNameAndInvalidPhoneReturns400() throws Exception {
        mockMvc.perform(post("/api/members")
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
    void createWithShortNationalIdReturns400() throws Exception {
        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Uwimana Claudine", "phone": "0788123456", "nationalId": "12345"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("nationalId: must be 16 digits"));
    }

    @Test
    void createWithDuplicatePhoneReturns409() throws Exception {
        when(memberService.create(any(MemberRequest.class))).thenThrow(
                new IllegalStateException("Phone number +250788123456 is already used by member MEM-0003."));

        mockMvc.perform(post("/api/members")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName": "Uwimana Claudine", "phone": "0788123456"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Phone number +250788123456 is already used by member MEM-0003."));
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
