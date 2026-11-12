package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** V41 adds the payment messages: a payment link, the two new kinds, one message per payment and kind. */
class PaymentSmsMigrationTest {

    @Test
    void theAllowedTypesAreExactlyTheValuesOfTheEnum() throws IOException {
        String sql = code();

        assertTrue(sql.contains(
                "CHECK (message_type IN ('DELIVERY_CODE', 'RECEIPT', 'PAYMENT', 'PAYMENT_REVERSED'))"));
        for (SmsMessageType type : SmsMessageType.values()) {
            assertTrue(sql.contains("'" + type.name() + "'"), type + " is missing from the CHECK");
            assertTrue(type.name().length() <= 20, "the column is VARCHAR(20)");
        }
    }

    @Test
    void aPaymentMessageNamesItsPaymentAndExistsOncePerKind() throws IOException {
        String sql = code();

        assertTrue(sql.contains("ADD COLUMN payment_id BIGINT REFERENCES produce_payment (id);"));
        assertTrue(sql.contains("CHECK (message_type NOT IN ('PAYMENT', 'PAYMENT_REVERSED') OR payment_id IS NOT NULL)"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX uq_sms_log_payment_type ON sms_log (payment_id, message_type) "
                + "WHERE payment_id IS NOT NULL;"));
    }

    @Test
    void onlyAddsAndNeverTouchesPayments() throws IOException {
        String upper = code().toUpperCase();

        for (String word : new String[] {"UPDATE ", "DELETE ", "INSERT ", "TRUNCATE", "DROP TABLE", "DROP COLUMN",
                "ALTER TABLE PRODUCE_PAYMENT", "LEDGER_ENTRY", "DISPUTE"}) {
            assertFalse(upper.contains(word), word);
        }
    }

    // The SQL without its comment lines.
    private static String code() throws IOException {
        try (InputStream in = PaymentSmsMigrationTest.class.getResourceAsStream(
                "/db/migration/V41__payment_sms.sql")) {
            StringBuilder code = new StringBuilder();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (!line.trim().startsWith("--")) {
                    code.append(line).append('\n');
                }
            }
            return code.toString();
        }
    }
}
