package com.coopaggregate.sms;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * The message for a payment to the member: the payment code, amount, method, day and what the member is still
     * owed after it. No name and no other member data. It fits one SMS: the cooperative's name is shortened if needed.
     */
    public static String payment(String language, String cooperativeName, String paymentCode, long amountRwf,
                                 String method, LocalDate paidOn, long owedRwf) {
        String day = DAY_MONTH.format(paidOn);
        String rest;
        if ("en".equals(language)) {
            rest = ": payment " + paymentCode + " of " + rwf(amountRwf) + " RWF (" + methodLabel(method, true)
                    + ") on " + day + ". You are now owed " + rwf(owedRwf) + " RWF. " + CONTACT_EN;
        } else {
            // Kinyarwanda: to be checked by a native speaker.
            rest = ": wishyuwe " + rwf(amountRwf) + " RWF (" + paymentCode + ", " + methodLabel(method, false)
                    + ") ku " + day + ". Ubu usigaje kwishyurwa " + rwf(owedRwf) + " RWF. " + CONTACT_RW;
        }
        return withCooperative(cooperativeName, rest, ONE_SMS);
    }

    /**
     * The message for a reversed payment: the payment code, amount, the manager's reason and what the member is owed
     * now. The reason is at most 100 characters, so it fits two SMS parts; the cooperative's name is shortened if
     * needed.
     */
    public static String paymentReversed(String language, String cooperativeName, String paymentCode,
                                         long amountRwf, String reason, long owedRwf) {
        String why = plain(reason).replaceAll("[.\\s]+$", "");
        String rest;
        if ("en".equals(language)) {
            rest = ": payment " + paymentCode + " of " + rwf(amountRwf) + " RWF was cancelled. Reason: " + why
                    + ". You are now owed " + rwf(owedRwf) + " RWF. " + CONTACT_EN;
        } else {
            // Kinyarwanda: to be checked by a native speaker.
            rest = ": kwishyurwa " + paymentCode + " kwa " + rwf(amountRwf) + " RWF kwahagaritswe. Impamvu: " + why
                    + ". Ubu usigaje kwishyurwa " + rwf(owedRwf) + " RWF. " + CONTACT_RW;
        }
        return withCooperative(cooperativeName, rest, TWO_SMS);
    }

    private static final String CONTACT_EN = "If this is wrong, contact the cooperative manager.";
    // Kinyarwanda: to be checked by a native speaker.
    private static final String CONTACT_RW = "Niba atari byo, vugana n'umuyobozi wa koperative.";
    // Two parts of a long SMS (153 characters each once joined).
    private static final int TWO_SMS = 306;

    private static String rwf(long amount) {
        return String.format(java.util.Locale.US, "%,d", amount);
    }

    private static String methodLabel(String method, boolean english) {
        return switch (method) {
            case "CASH" -> english ? "cash" : "mu ntoki";
            case "MOMO" -> "MoMo";
            case "BANK" -> english ? "bank" : "banki";
            default -> plain(method);
        };
    }

    // The cooperative's name (plain characters, CoopAggregate when empty) before the rest, shortened so the whole
    // fits within limit. The rest is never cut.
    private static String withCooperative(String cooperativeName, String rest, int limit) {
        String cooperative = plain(cooperativeName);
        if (cooperative.isEmpty()) {
            cooperative = "CoopAggregate";
        }
        int room = Math.max(1, limit - rest.length());
        if (cooperative.length() > room) {
            cooperative = cooperative.substring(0, room).trim();
        }
        return cooperative + rest;
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
