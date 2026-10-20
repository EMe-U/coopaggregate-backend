package com.coopaggregate.sms;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * Receives members' SMS replies from the SMS provider. It needs no login, so it is protected by a secret
 * (SMS_INBOUND_SECRET) given as ?token= in the callback URL or in the X-Sms-Secret header.
 */
@RestController
@RequestMapping("/api/sms")
@Tag(name = "SMS", description = "Members' SMS replies")
public class InboundSmsController {

    private final InboundSmsService inboundSmsService;
    private final String secret;

    public InboundSmsController(InboundSmsService inboundSmsService,
                                @Value("${app.sms.inbound-secret}") String secret) {
        this.inboundSmsService = inboundSmsService;
        this.secret = secret;
    }

    @PostMapping("/inbound")
    @Operation(summary = "Webhook for SMS replies (form fields from and text). '1 RCT-7K2Q' confirms a delivery, "
            + "'2 RCT-7K2Q note' disputes it")
    public ResponseEntity<String> inbound(@RequestParam(required = false) String token,
                                          @RequestHeader(name = "X-Sms-Secret", required = false) String header,
                                          @RequestParam(required = false) String from,
                                          @RequestParam(required = false) String text) {
        if (!authorized(token) && !authorized(header)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Forbidden");
        }
        // Always 200, so the provider does not keep resending replies that were ignored.
        return ResponseEntity.ok(inboundSmsService.handle(from, text).name());
    }

    // Constant-time comparison, so the secret cannot be guessed from response times.
    private boolean authorized(String given) {
        return !secret.isBlank() && given != null
                && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),
                        given.getBytes(StandardCharsets.UTF_8));
    }
}
