package com.coopaggregate.setting;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

@WebMvcTest(SettingController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = "app.jwt.secret=test-secret-that-is-at-least-32-characters")
class SettingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtEncoder jwtEncoder;

    @MockitoBean
    private SettingService settingService;

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
    void moreThanTenCommitteeEmailsAreRejected() throws Exception {
        StringBuilder emails = new StringBuilder();
        for (int i = 1; i <= 11; i++) {
            emails.append(i > 1 ? "," : "").append("\"member").append(i).append("@example.com\"");
        }

        putSettings("{\"smsEnabled\": true, \"minOrderKg\": 100, \"committeeEmails\": [" + emails + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("committeeEmails: can have at most 10 emails"));
        verifyNoInteractions(settingService);
    }

    @Test
    void invalidCommitteeEmailIsRejected() throws Exception {
        putSettings("{\"smsEnabled\": true, \"minOrderKg\": 100, "
                + "\"committeeEmails\": [\"chair@example.com\", \"not-an-email\"]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("committeeEmails[1]: must be a valid email address"));
    }

    @Test
    void minimumOrderMustBePositive() throws Exception {
        putSettings("{\"smsEnabled\": true, \"minOrderKg\": 0, \"committeeEmails\": []}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("minOrderKg: must be more than 0"));
    }

    private ResultActions putSettings(String body) throws Exception {
        return mockMvc.perform(put("/api/settings").header(HttpHeaders.AUTHORIZATION, bearerToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
