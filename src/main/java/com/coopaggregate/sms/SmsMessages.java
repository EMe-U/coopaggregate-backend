package com.coopaggregate.sms;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.coopaggregate.common.KigaliTime;

/** The texts members receive, in English or Kinyarwanda. */
public final class SmsMessages {

    /** Stands in for the one-time code in sms_log; the real code is only put in when the SMS is sent. */
    public static final String CODE_MASK = "******";

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

    private SmsMessages() {
    }

    /**
     * The message for a new delivery, with the one-time code the member gives the manager if it is correct.
     * Members can still reply "1 <receipt>" to confirm or "2 <receipt>" to dispute it.
     */
    public static String deliveryCode(String language, String receiptCode, BigDecimal quantityKg, String gradeName,
                                      Instant deliveredAt, String code) {
        String kg = quantityKg.stripTrailingZeros().toPlainString();
        String day = DAY_MONTH.format(deliveredAt.atZone(KigaliTime.ZONE));

        if ("en".equals(language)) {
            return "CoopAggregate: " + receiptCode + ", " + kg + " kg " + gradeName + ", " + day
                    + ". If correct, give the manager code " + code + ". If wrong, don't give the code, reply 2 "
                    + receiptCode + ".";
        }
        // Kinyarwanda: to be checked by a native speaker.
        return "CoopAggregate: " + receiptCode + ", kg " + kg + " " + gradeName + ", " + day
                + ". Niba ari ukuri, ha umuyobozi kode " + code + ". Niba atari ukuri, ntutange kode, subiza 2 "
                + receiptCode + ".";
    }
}
