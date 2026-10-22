package com.coopaggregate.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.coopaggregate.buyer.BuyerRequestStatus;
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

    @MockitoBean
    private PublicRequestService requestService;

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

    // --- Purchase requests ---

    private static final String VALID_REQUEST = """
            {"name": "Kalisa Traders", "phone": "0788123456", "email": "kalisa@example.com", "gradeCode": "A",
             "quantityKg": 250, "note": "Friday please"}
            """;

    @Test
    void requestIsSentWithoutLoggingIn() throws Exception {
        when(requestService.submit(any(), anyString())).thenReturn(
                new PublicRequestCreatedResponse("BR-2026-7K2Q", BuyerRequestStatus.PENDING));

        mockMvc.perform(post("/api/public/requests").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("BR-2026-7K2Q"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void theAddressOfTheBuyerIsUsedForTheLimit() throws Exception {
        when(requestService.submit(any(), anyString())).thenReturn(
                new PublicRequestCreatedResponse("BR-2026-7K2Q", BuyerRequestStatus.PENDING));

        mockMvc.perform(post("/api/public/requests").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST)
                .with(request -> {
                    request.setRemoteAddr("203.0.113.9");
                    return request;
                })).andExpect(status().isCreated());

        verify(requestService).submit(any(), eq("203.0.113.9"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{\"phone\": \"0788123456\", \"gradeCode\": \"A\", \"quantityKg\": 250} | name: is required",
            "{\"name\": \"K\", \"phone\": \"12345\", \"gradeCode\": \"A\", \"quantityKg\": 250} | phone: must be a Rwandan mobile number: 07XXXXXXXX, 2507XXXXXXXX or +2507XXXXXXXX",
            "{\"name\": \"K\", \"phone\": \"0788123456\", \"email\": \"nope\", \"gradeCode\": \"A\", \"quantityKg\": 250} | email: must be a valid email address",
            "{\"name\": \"K\", \"phone\": \"0788123456\", \"quantityKg\": 250} | gradeCode: is required",
            "{\"name\": \"K\", \"phone\": \"0788123456\", \"gradeCode\": \"A\"} | quantityKg: is required",
            "{\"name\": \"K\", \"phone\": \"0788123456\", \"gradeCode\": \"A\", \"quantityKg\": 0} | quantityKg: must be more than 0",
            "{\"name\": \"K\", \"phone\": \"0788123456\", \"gradeCode\": \"A\", \"quantityKg\": 250.123} | quantityKg: must have at most 2 decimals"})
    void invalidRequestIs400WithTheFieldMessage(String body, String message) throws Exception {
        mockMvc.perform(post("/api/public/requests").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
        verifyNoInteractions(requestService);
    }

    @Test
    void tooLongTextIsRefused() throws Exception {
        String longNote = "x".repeat(1001);

        mockMvc.perform(post("/api/public/requests").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"K\", \"phone\": \"0788123456\", \"gradeCode\": \"A\", "
                                + "\"quantityKg\": 250, \"note\": \"" + longNote + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("note: must be at most 1000 characters"));
    }

    @Test
    void tooManyRequestsIs429WithRetryAfter() throws Exception {
        when(requestService.submit(any(), anyString())).thenThrow(new TooManyRequestsException(120));

        mockMvc.perform(post("/api/public/requests").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "120"))
                .andExpect(jsonPath("$.message").value("Too many requests. Please try again later."));
    }

    @Test
    void statusIsShownWithTheMatchingPhone() throws Exception {
        when(requestService.status(eq("BR-2026-7K2Q"), eq("0788123456"), anyString())).thenReturn(
                new PublicRequestStatusResponse("BR-2026-7K2Q", BuyerRequestStatus.ACCEPTED, "We will call you."));

        mockMvc.perform(get("/api/public/requests/BR-2026-7K2Q").param("phone", "0788123456"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("BR-2026-7K2Q"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.message").value("We will call you."))
                .andExpect(jsonPath("$.managerNote").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());
    }

    @Test
    void unknownCodeOrWrongPhoneIs404WithTheSameMessage() throws Exception {
        when(requestService.status(anyString(), any(), anyString()))
                .thenThrow(new jakarta.persistence.EntityNotFoundException("Request not found."));

        mockMvc.perform(get("/api/public/requests/BR-2026-ZZZZ").param("phone", "0788123456"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Request not found."));
        mockMvc.perform(get("/api/public/requests/BR-2026-7K2Q"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Request not found."));
    }

    @Test
    void anotherWebsiteIsNotAllowed() throws Exception {
        mockMvc.perform(get("/api/public/grades").header("Origin", "https://evil.example"))
                .andExpect(status().isForbidden());
    }
}
