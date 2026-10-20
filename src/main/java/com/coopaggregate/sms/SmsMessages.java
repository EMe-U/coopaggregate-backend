package com.coopaggregate.sms;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.delivery.ConfirmationStatus;

/** The texts members receive, in English or Kinyarwanda. */
public final class SmsMessages {

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("dd/MM");

    private SmsMessages() {
    }

    /** The message for a new delivery. Members reply "1 <code>" if it is right and "2 <code>" if it is wrong. */
    public static String delivery(String language, ConfirmationStatus status, String receiptCode,
                                  BigDecimal quantityKg, String gradeName, Instant deliveredAt) {
        String kg = quantityKg.stripTrailingZeros().toPlainString();
        String day = DAY_MONTH.format(deliveredAt.atZone(KigaliTime.ZONE));
        boolean english = "en".equals(language);

        if (status == ConfirmationStatus.CONFIRMED) {
            return english
                    ? "CoopAggregate: you confirmed " + receiptCode + ", " + kg + " kg " + gradeName + ", " + day
                            + ". If this was not you, reply 2 " + receiptCode + "."
                    : "CoopAggregate: wemeje " + receiptCode + ", kg " + kg + " " + gradeName + ", " + day
                            + ". Niba atari wowe, subiza 2 " + receiptCode + ".";
        }
        return english
                ? "CoopAggregate: " + receiptCode + ", " + kg + " kg " + gradeName + ", " + day
                        + ". Reply 1 " + receiptCode + " if correct, 2 " + receiptCode + " if wrong."
                : "CoopAggregate: " + receiptCode + ", kg " + kg + " " + gradeName + ", " + day
                        + ". Subiza 1 " + receiptCode + " niba ari ukuri, 2 " + receiptCode + " niba atari ukuri.";
    }
}
