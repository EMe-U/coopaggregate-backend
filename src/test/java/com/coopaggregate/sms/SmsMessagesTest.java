package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.coopaggregate.delivery.ConfirmationStatus;

class SmsMessagesTest {

    private static final Instant OCT_19_MORNING = Instant.parse("2026-10-19T07:30:00Z");

    @Test
    void confirmedDeliveryInEnglish() {
        assertEquals("CoopAggregate: you confirmed RCT-7K2Q, 300 kg Big, 19/10. If this was not you, "
                        + "reply 2 RCT-7K2Q.",
                SmsMessages.delivery("en", ConfirmationStatus.CONFIRMED, "RCT-7K2Q", new BigDecimal("300.00"),
                        "Big", OCT_19_MORNING));
    }

    @Test
    void pendingDeliveryInEnglish() {
        assertEquals("CoopAggregate: RCT-7K2Q, 300 kg Big, 19/10. Reply 1 RCT-7K2Q if correct, "
                        + "2 RCT-7K2Q if wrong.",
                SmsMessages.delivery("en", ConfirmationStatus.PENDING, "RCT-7K2Q", new BigDecimal("300.00"),
                        "Big", OCT_19_MORNING));
    }

    @Test
    void confirmedDeliveryInKinyarwanda() {
        String text = SmsMessages.delivery("rw", ConfirmationStatus.CONFIRMED, "RCT-7K2Q",
                new BigDecimal("300.00"), "Big", OCT_19_MORNING);

        assertEquals("CoopAggregate: wemeje RCT-7K2Q, kg 300 Big, 19/10. Niba atari wowe, subiza 2 RCT-7K2Q.", text);
    }

    @Test
    void pendingDeliveryInKinyarwanda() {
        String text = SmsMessages.delivery("rw", ConfirmationStatus.PENDING, "RCT-7K2Q",
                new BigDecimal("300.00"), "Big", OCT_19_MORNING);

        assertEquals("CoopAggregate: RCT-7K2Q, kg 300 Big, 19/10. Subiza 1 RCT-7K2Q niba ari ukuri, "
                + "2 RCT-7K2Q niba atari ukuri.", text);
    }

    @Test
    void anyLanguageOtherThanEnglishGetsKinyarwanda() {
        assertTrue(SmsMessages.delivery(null, ConfirmationStatus.PENDING, "RCT-7K2Q", new BigDecimal("1"), "Big",
                OCT_19_MORNING).contains("Subiza"));
    }

    @Test
    void kilogramsDropTrailingZeros() {
        assertTrue(SmsMessages.delivery("en", ConfirmationStatus.PENDING, "RCT-7K2Q", new BigDecimal("120.50"),
                "Normal", OCT_19_MORNING).contains("120.5 kg Normal"));
    }

    @Test
    void dateIsTheKigaliDay() {
        // 23:30 UTC on 19 October is already 01:30 on 20 October in Kigali.
        assertTrue(SmsMessages.delivery("en", ConfirmationStatus.PENDING, "RCT-7K2Q", new BigDecimal("1"), "Big",
                Instant.parse("2026-10-19T23:30:00Z")).contains(", 20/10."));
    }

    @Test
    void messagesFitInOneSms() {
        String longest = SmsMessages.delivery("rw", ConfirmationStatus.PENDING, "RCT-7K2Q",
                new BigDecimal("4999.99"), "Normal", OCT_19_MORNING);

        assertTrue(longest.length() <= 160, longest.length() + " characters");
    }
}
