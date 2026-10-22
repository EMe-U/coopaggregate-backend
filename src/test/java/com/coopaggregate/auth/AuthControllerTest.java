package com.coopaggregate.auth;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

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

import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;
import com.coopaggregate.error.BadRequestException;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private AuthService authService;

    @Test
    void loginReturnsToken() throws Exception {
        Instant expiresAt = Instant.parse("2026-10-07T20:00:00Z");
        when(authService.login(any(LoginRequest.class)))
                .thenReturn(new LoginResponse("test-token", expiresAt, "Test Manager"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "manager@example.com", "password": "correct-password"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("test-token"))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-07T20:00:00Z"))
                .andExpect(jsonPath("$.managerName").value("Test Manager"));
    }

    @Test
    void wrongPasswordReturns401() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenThrow(new InvalidLoginException());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "manager@example.com", "password": "wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void passwordChangeReturns204() throws Exception {
        changePassword("{\"currentPassword\": \"old-password\", \"newPassword\": \"new-password\"}")
                .andExpect(status().isNoContent());
        verify(authService).changePassword(eq("manager@example.com"), any(ChangePasswordRequest.class));
    }

    @Test
    void shortNewPasswordIsRejected() throws Exception {
        changePassword("{\"currentPassword\": \"old-password\", \"newPassword\": \"short\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("newPassword: must be at least 8 characters"));
        verifyNoInteractions(authService);
    }

    @Test
    void wrongCurrentPasswordReturns400() throws Exception {
        doThrow(new BadRequestException("The current password is incorrect."))
                .when(authService).changePassword(any(), any());

        changePassword("{\"currentPassword\": \"wrong\", \"newPassword\": \"new-password\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The current password is incorrect."));
    }

    @Test
    void passwordChangeNeedsToken() throws Exception {
        mockMvc.perform(post("/api/auth/change-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"old-password\", \"newPassword\": \"new-password\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(authService);
    }

    private ResultActions changePassword(String body) throws Exception {
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("manager@example.com").issuedAt(Instant.now())
                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS)).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return mockMvc.perform(post("/api/auth/change-password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
