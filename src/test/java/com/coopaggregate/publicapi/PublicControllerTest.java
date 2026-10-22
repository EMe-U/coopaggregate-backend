package com.coopaggregate.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;

@WebMvcTest(PublicController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = {"app.jwt.secret=test-secret-that-is-at-least-32-characters",
        "app.frontend-url=https://coopaggregate-frontend.onrender.com"})
class PublicControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PublicService publicService;

    @Test
    void gradesAreShownWithoutLoggingIn() throws Exception {
        when(publicService.grades()).thenReturn(List.of(new PublicGradeResponse("A", "Big", "Binini",
                "Large potatoes", "Bigger than a fist", "50 kg bags")));

        mockMvc.perform(get("/api/public/grades"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("A"))
                .andExpect(jsonPath("$[0].name").value("Big"))
                .andExpect(jsonPath("$[0].nameRw").value("Binini"))
                .andExpect(jsonPath("$[0].description").value("Large potatoes"))
                .andExpect(jsonPath("$[0].sizeInfo").value("Bigger than a fist"))
                .andExpect(jsonPath("$[0].packaging").value("50 kg bags"))
                .andExpect(jsonPath("$[0].floorPricePerKg").doesNotExist())
                .andExpect(jsonPath("$[0].id").doesNotExist());
    }

    @Test
    void infoIsShownWithoutLoggingIn() throws Exception {
        when(publicService.info()).thenReturn(new PublicInfoResponse("Koperative Intagamburuzwa", "Busogo, Musanze",
                "info@intagamburuzwa.rw", new BigDecimal("100")));

        mockMvc.perform(get("/api/public/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cooperativeName").value("Koperative Intagamburuzwa"))
                .andExpect(jsonPath("$.location").value("Busogo, Musanze"))
                .andExpect(jsonPath("$.cooperativeEmail").value("info@intagamburuzwa.rw"))
                .andExpect(jsonPath("$.minOrderKg").value(100));
    }

    @Test
    void responsesHaveNoFieldBeyondTheAgreedOnes() {
        assertEquals(List.of("code", "name", "nameRw", "description", "sizeInfo", "packaging"),
                Arrays.stream(PublicGradeResponse.class.getRecordComponents()).map(c -> c.getName()).toList());
        assertEquals(List.of("cooperativeName", "location", "cooperativeEmail", "minOrderKg"),
                Arrays.stream(PublicInfoResponse.class.getRecordComponents()).map(c -> c.getName()).toList());
    }

    @Test
    void otherEndpointsStillNeedALogin() throws Exception {
        mockMvc.perform(get("/api/buyer-requests")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/members")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/lots/stock")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/ledger")).andExpect(status().isUnauthorized());
    }

    @Test
    void theFrontendMayCallThePublicEndpointsFromTheBrowser() throws Exception {
        String frontend = "https://coopaggregate-frontend.onrender.com";

        mockMvc.perform(options("/api/public/requests")
                        .header("Origin", frontend)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", frontend));
        mockMvc.perform(get("/api/public/grades").header("Origin", frontend))
                .andExpect(header().string("Access-Control-Allow-Origin", frontend));
    }

    @Test
    void anotherWebsiteIsNotAllowed() throws Exception {
        mockMvc.perform(get("/api/public/grades").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }
}
