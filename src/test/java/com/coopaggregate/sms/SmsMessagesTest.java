package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class SmsMessagesTest {

    private static final Instant OCT_21_MORNING = Instant.parse("2026-10-21T07:30:00Z");

    @Test
    void codeMessageInEnglish() {
        assertEquals("CoopAggregate: RCT-7K2Q, 300 kg Big, 21/10. If correct, give the manager code 482193. "
                        + "If wrong, don't give the code, reply 2 RCT-7K2Q.",
                SmsMessages.deliveryCode("en", "RCT-7K2Q", new BigDecimal("300.00"), "Big", OCT_21_MORNING,
                        "482193"));
    }

    @Test
    void codeMessageInKinyarwanda() {
        assertEquals("CoopAggregate: RCT-7K2Q, kg 300 Big, 21/10. Niba ari ukuri, ha umuyobozi kode 482193. "
                        + "Niba atari ukuri, ntutange kode, subiza 2 RCT-7K2Q.",
                SmsMessages.deliveryCode("rw", "RCT-7K2Q", new BigDecimal("300.00"), "Big", OCT_21_MORNING,
                        "482193"));
    }

    @Test
    void anyLanguageOtherThanEnglishGetsKinyarwanda() {
        assertTrue(SmsMessages.deliveryCode(null, "RCT-7K2Q", new BigDecimal("1"), "Big", OCT_21_MORNING,
                "482193").contains("subiza 2"));
    }

    @Test
    void kilogramsDropTrailingZeros() {
        assertTrue(SmsMessages.deliveryCode("en", "RCT-7K2Q", new BigDecimal("120.50"), "Normal", OCT_21_MORNING,
                "482193").contains("120.5 kg Normal"));
    }

    @Test
    void dateIsTheKigaliDay() {
        // 23:30 UTC on 20 October is already 01:30 on 21 October in Kigali.
        assertTrue(SmsMessages.deliveryCode("en", "RCT-7K2Q", new BigDecimal("1"), "Big",
                Instant.parse("2026-10-20T23:30:00Z"), "482193").contains(", 21/10."));
    }

    @Test
    void messagesFitInOneSms() {
        for (String language : new String[] {"en", "rw"}) {
            String longest = SmsMessages.deliveryCode(language, "RCT-7K2Q", new BigDecimal("4999.99"), "Normal",
                    OCT_21_MORNING, "482193");

            assertTrue(longest.length() <= 160, language + ": " + longest.length() + " characters");
        }
    }
}
