package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.coopaggregate.config.JwtConfig;
import com.coopaggregate.config.SecurityConfig;

@WebMvcTest(InboundSmsController.class)
@Import({SecurityConfig.class, JwtConfig.class})
@TestPropertySource(properties = {
        "app.jwt.secret=test-secret-that-is-at-least-32-characters",
        "app.sms.inbound-secret=inbound-secret-123"
})
class InboundSmsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InboundSmsService inboundSmsService;

    @Test
    void replyWithTheTokenInTheUrlIsHandledWithoutLogin() throws Exception {
        when(inboundSmsService.handle("+250788123456", "1 RCT-7K2Q")).thenReturn(InboundSmsService.Result.CONFIRMED);

        mockMvc.perform(post("/api/sms/inbound").param("token", "inbound-secret-123")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("from", "+250788123456").param("text", "1 RCT-7K2Q"))
                .andExpect(status().isOk())
                .andExpect(content().string("CONFIRMED"));
    }

    @Test
    void replyWithTheTokenInTheHeaderIsHandled() throws Exception {
        when(inboundSmsService.handle(any(), any())).thenReturn(InboundSmsService.Result.IGNORED);

        mockMvc.perform(post("/api/sms/inbound").header("X-Sms-Secret", "inbound-secret-123")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("from", "+250788123456").param("text", "hello"))
                .andExpect(status().isOk())
                .andExpect(content().string("IGNORED"));
    }

    @Test
    void replyWithoutTheTokenIsRefused() throws Exception {
        mockMvc.perform(post("/api/sms/inbound").contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("from", "+250788123456").param("text", "2 RCT-7K2Q"))
                .andExpect(status().isForbidden());

        verify(inboundSmsService, never()).handle(any(), any());
    }

    @Test
    void replyWithAWrongTokenIsRefused() throws Exception {
        mockMvc.perform(post("/api/sms/inbound").param("token", "guess")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("from", "+250788123456").param("text", "2 RCT-7K2Q"))
                .andExpect(status().isForbidden());

        verify(inboundSmsService, never()).handle(any(), any());
    }

    @Test
    void noSecretConfiguredRefusesEveryReply() throws Exception {
        InboundSmsController controller = new InboundSmsController(inboundSmsService, "");

        assertEquals(403,
                controller.inbound("", "", "+250788123456", "1 RCT-7K2Q").getStatusCode().value());
        verify(inboundSmsService, never()).handle(any(), any());
    }
}
