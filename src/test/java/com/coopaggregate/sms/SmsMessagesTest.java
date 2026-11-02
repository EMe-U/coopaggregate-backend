package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                        "482193", true));
    }

    @Test
    void codeMessageInKinyarwanda() {
        assertEquals("CoopAggregate: RCT-7K2Q, kg 300 Big, 21/10. Niba ari ukuri, ha umuyobozi kode 482193. "
                        + "Niba atari ukuri, ntutange kode, subiza 2 RCT-7K2Q.",
                SmsMessages.deliveryCode("rw", "RCT-7K2Q", new BigDecimal("300.00"), "Big", OCT_21_MORNING,
                        "482193", true));
    }

    @Test
    void withoutRepliesTheEnglishCodeMessageSaysToTellTheManager() {
        assertEquals("CoopAggregate: RCT-7K2Q, 300 kg Big, 21/10. If correct, give the manager code 482193. "
                        + "If the code is wrong, tell the manager.",
                SmsMessages.deliveryCode("en", "RCT-7K2Q", new BigDecimal("300.00"), "Big", OCT_21_MORNING,
                        "482193", false));
    }

    @Test
    void withoutRepliesTheKinyarwandaCodeMessageSaysToTellTheManager() {
        assertEquals("CoopAggregate: RCT-7K2Q, kg 300 Big, 21/10. Niba ari ukuri, ha umuyobozi kode 482193. "
                        + "Niba kode atari yo, bwira umuyobozi.",
                SmsMessages.deliveryCode("rw", "RCT-7K2Q", new BigDecimal("300.00"), "Big", OCT_21_MORNING,
                        "482193", false));
    }

    @Test
    void withoutRepliesNoMessageAsksForAReply() {
        for (String language : new String[] {"en", "rw", null}) {
            String text = SmsMessages.deliveryCode(language, "RCT-7K2Q", new BigDecimal("1"), "Big", OCT_21_MORNING,
                    "482193", false);

            assertFalse(text.contains("reply"), text);
            assertFalse(text.contains("subiza"), text);
            assertFalse(text.contains(" 1 RCT-"), text);
            assertFalse(text.contains(" 2 RCT-"), text);
        }
    }

    @Test
    void anyLanguageOtherThanEnglishGetsKinyarwanda() {
        assertTrue(SmsMessages.deliveryCode(null, "RCT-7K2Q", new BigDecimal("1"), "Big", OCT_21_MORNING,
                "482193", true).contains("subiza 2"));
    }

    @Test
    void kilogramsDropTrailingZeros() {
        assertTrue(SmsMessages.deliveryCode("en", "RCT-7K2Q", new BigDecimal("120.50"), "Normal", OCT_21_MORNING,
                "482193", true).contains("120.5 kg Normal"));
    }

    @Test
    void dateIsTheKigaliDay() {
        // 23:30 UTC on 20 October is already 01:30 on 21 October in Kigali.
        assertTrue(SmsMessages.deliveryCode("en", "RCT-7K2Q", new BigDecimal("1"), "Big",
                Instant.parse("2026-10-20T23:30:00Z"), "482193", true).contains(", 21/10."));
    }

    @Test
    void messagesFitInOneSms() {
        for (String language : new String[] {"en", "rw"}) {
          for (boolean replies : new boolean[] {true, false}) {
            String longest = SmsMessages.deliveryCode(language, "RCT-7K2Q", new BigDecimal("4999.99"), "Normal",
                    OCT_21_MORNING, "482193", replies);

            assertTrue(longest.length() <= 160, language + ": " + longest.length() + " characters");
          }
        }
    }
}
