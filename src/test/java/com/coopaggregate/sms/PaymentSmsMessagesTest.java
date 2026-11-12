package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** The payment and reversal texts: amount, balance, no name, no "reply", within one or two SMS parts. */
class PaymentSmsMessagesTest {

    private static final String COOPERATIVE = "Koperative Intagamburuzwa";
    private static final LocalDate PAID_ON = LocalDate.of(2026, 11, 12);
    private static final String LONG_COOPERATIVE = "Koperative y'Abahinzi b'Ibirayi ba Musanze na Nyabihu Twese Hamwe";

    @Test
    void thePaymentMessageInEnglish() {
        assertEquals("Koperative Intagamburuzwa: payment PAY-0007 of 25,000 RWF (MoMo) on 12/11. You are now owed "
                        + "10,000 RWF. If this is wrong, contact the cooperative manager.",
                SmsMessages.payment("en", COOPERATIVE, "PAY-0007", 25_000L, "MOMO", PAID_ON, 10_000L));
    }

    @Test
    void thePaymentMessageInKinyarwanda() {
        assertEquals("Koperative Intagamburuzwa: wishyuwe 25,000 RWF (PAY-0007, MoMo) ku 12/11. Ubu usigaje "
                        + "kwishyurwa 10,000 RWF. Niba atari byo, vugana n'umuyobozi wa koperative.",
                SmsMessages.payment("rw", COOPERATIVE, "PAY-0007", 25_000L, "MOMO", PAID_ON, 10_000L));
    }

    @Test
    void theMethodIsWrittenPlainly() {
        assertTrue(SmsMessages.payment("en", COOPERATIVE, "PAY-0007", 1L, "CASH", PAID_ON, 0L).contains("(cash)"));
        assertTrue(SmsMessages.payment("en", COOPERATIVE, "PAY-0007", 1L, "BANK", PAID_ON, 0L).contains("(bank)"));
        assertTrue(SmsMessages.payment("rw", COOPERATIVE, "PAY-0007", 1L, "CASH", PAID_ON, 0L)
                .contains("PAY-0007, mu ntoki)"));
    }

    @Test
    void aPaymentMessageAlwaysFitsOneSmsAndOnlyTheCooperativesNameIsShortened() {
        for (String language : new String[] {"en", "rw"}) {
            for (String method : new String[] {"CASH", "MOMO", "BANK"}) {
                String text = SmsMessages.payment(language, LONG_COOPERATIVE, "PAY-12345", 9_999_999L, method,
                        PAID_ON, 9_999_999L);

                assertTrue(text.length() <= 160, text.length() + ": " + text);
                assertTrue(text.startsWith("Koperative y'"), text);
                assertTrue(text.contains("PAY-12345") && text.contains("9,999,999 RWF"), text);
                assertTrue(text.endsWith(language.equals("en") ? "contact the cooperative manager."
                        : "vugana n'umuyobozi wa koperative."), text);
            }
        }
    }

    @Test
    void theReversalMessageInEnglish() {
        assertEquals("Koperative Intagamburuzwa: payment PAY-0007 of 25,000 RWF was cancelled. Reason: Paid twice. "
                        + "You are now owed 35,000 RWF. If this is wrong, contact the cooperative manager.",
                SmsMessages.paymentReversed("en", COOPERATIVE, "PAY-0007", 25_000L, "Paid twice.", 35_000L));
    }

    @Test
    void theReversalMessageInKinyarwanda() {
        assertEquals("Koperative Intagamburuzwa: kwishyurwa PAY-0007 kwa 25,000 RWF kwahagaritswe. Impamvu: "
                        + "Yishyuwe kabiri. Ubu usigaje kwishyurwa 35,000 RWF. Niba atari byo, vugana n'umuyobozi wa "
                        + "koperative.",
                SmsMessages.paymentReversed("rw", COOPERATIVE, "PAY-0007", 25_000L, "Yishyuwe kabiri", 35_000L));
    }

    @Test
    void aReversalWithTheLongestReasonFitsTwoSmsPartsAndKeepsTheWholeReason() {
        String reason = "R".repeat(100);
        for (String language : new String[] {"en", "rw"}) {
            String text = SmsMessages.paymentReversed(language, LONG_COOPERATIVE, "PAY-12345", 9_999_999L, reason,
                    9_999_999L);

            assertTrue(text.length() <= 306, text.length() + ": " + text);
            assertTrue(text.contains(reason), text);
        }
    }

    @Test
    void theReasonIsWrittenInPlainCharacters() {
        String text = SmsMessages.paymentReversed("en", COOPERATIVE, "PAY-0007", 25_000L, "Paiement en double é",
                35_000L);

        assertTrue(text.contains("Reason: Paiement en double e. "), text);
    }

    @Test
    void noMessageAsksTheMemberToReply() {
        String[] texts = {
                SmsMessages.payment("en", COOPERATIVE, "PAY-0007", 25_000L, "MOMO", PAID_ON, 10_000L),
                SmsMessages.payment("rw", COOPERATIVE, "PAY-0007", 25_000L, "MOMO", PAID_ON, 10_000L),
                SmsMessages.paymentReversed("en", COOPERATIVE, "PAY-0007", 25_000L, "Paid twice", 35_000L),
                SmsMessages.paymentReversed("rw", COOPERATIVE, "PAY-0007", 25_000L, "Paid twice", 35_000L)};
        for (String text : texts) {
            assertFalse(text.toLowerCase().contains("reply"), text);
            assertFalse(text.toLowerCase().contains("subiza"), text);
            assertFalse(text.contains("2 PAY-"), text);
        }
    }
}
