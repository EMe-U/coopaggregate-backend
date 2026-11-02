package com.coopaggregate.sms;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.coopaggregate.common.KigaliTime;

/** The texts members receive, in English or Kinyarwanda. */
public final class SmsMessages {

    /** Stands in for the one-time code in sms_log; the real code is only put in when the SMS is sent. */
    public static final String CODE_MASK = "******";

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");
    private static final int ONE_SMS = 160;

    private SmsMessages() {
    }

    /**
     * The message for a new delivery, with the one-time code the member gives the manager if it is correct. With
     * replies (the provider passes them on to the webhook), the member is told to reply "2 <receipt>" when it is
     * wrong; without them, to tell the manager.
     */
    public static String deliveryCode(String language, String receiptCode, BigDecimal quantityKg, String gradeName,
                                      Instant deliveredAt, String code, boolean replies) {
        String kg = quantityKg.stripTrailingZeros().toPlainString();
        String day = DAY_MONTH.format(deliveredAt.atZone(KigaliTime.ZONE));

        if ("en".equals(language)) {
            return "CoopAggregate: " + receiptCode + ", " + kg + " kg " + gradeName + ", " + day
                    + ". If correct, give the manager code " + code + ". "
                    + (replies ? "If wrong, don't give the code, reply 2 " + receiptCode + "."
                            : "If the code is wrong, tell the manager.");
        }
        // Kinyarwanda: to be checked by a native speaker.
        return "CoopAggregate: " + receiptCode + ", kg " + kg + " " + gradeName + ", " + day
                + ". Niba ari ukuri, ha umuyobozi kode " + code + ". "
                + (replies ? "Niba atari ukuri, ntutange kode, subiza 2 " + receiptCode + "."
                        : "Niba kode atari yo, bwira umuyobozi.");
    }

    /**
     * The receipt once a delivery is confirmed: the cooperative, the receipt code, kg, grade and day. No money, no
     * balance and no name. The cooperative's and grade's names are typed by the manager, so they are written in
     * plain characters and the cooperative's name is shortened when the whole would not fit in one SMS.
     */
    public static String receipt(String language, String cooperativeName, String receiptCode, BigDecimal quantityKg,
                                 String gradeName, Instant deliveredAt) {
        String kg = quantityKg.stripTrailingZeros().toPlainString();
        String day = DAY_MONTH.format(deliveredAt.atZone(KigaliTime.ZONE));
        String grade = plain(gradeName);

        String rest;
        if ("en".equals(language)) {
            rest = ": delivery " + receiptCode + " confirmed. " + kg + " kg " + grade + ", " + day + ". Thank you.";
        } else {
            // Kinyarwanda: to be checked by a native speaker.
            rest = ": ibyo watanze " + receiptCode + " byemejwe. kg " + kg + " " + grade + ", " + day + ". Murakoze.";
        }
        String cooperative = plain(cooperativeName);
        if (cooperative.isEmpty()) {
            cooperative = "CoopAggregate";
        }
        int room = Math.max(1, ONE_SMS - rest.length());
        if (cooperative.length() > room) {
            cooperative = cooperative.substring(0, room).trim();
        }
        String text = cooperative + rest;
        return text.length() > ONE_SMS ? text.substring(0, ONE_SMS) : text;
    }

    // Letters without accents, digits and plain punctuation only: every one is a single character in an SMS.
    static String plain(String text) {
        if (text == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return noAccents.replaceAll("[^A-Za-z0-9 .,:;'()&/+\\-!?#%\"=@_]", " ").replaceAll("\\s+", " ").trim();
    }
}
