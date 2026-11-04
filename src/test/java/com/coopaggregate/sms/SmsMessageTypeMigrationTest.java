package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** V35 tags every existing message as a code message and keeps one message per delivery, kind and number. */
class SmsMessageTypeMigrationTest {

    @Test
    void existingMessagesBecomeCodeMessagesAndNothingIsRemoved() throws IOException {
        String sql = code();

        assertTrue(sql.contains("ADD COLUMN message_type VARCHAR(20) NOT NULL DEFAULT 'DELIVERY_CODE'"));
        String upper = sql.toUpperCase();
        assertFalse(upper.contains("UPDATE "));
        assertFalse(upper.contains("DELETE "));
        assertFalse(upper.contains("DROP TABLE"));
        assertFalse(upper.contains("DROP COLUMN"));
    }

    @Test
    void theAllowedTypesAreExactlyTheValuesOfTheEnum() throws IOException {
        String sql = code();

        assertTrue(sql.contains("CHECK (message_type IN ('DELIVERY_CODE', 'RECEIPT'))"));
        assertEquals(2, SmsMessageType.values().length);
        for (SmsMessageType type : SmsMessageType.values()) {
            assertTrue(type.name().length() <= 20, "the column is VARCHAR(20)");
        }
    }

    @Test
    void theUniqueIndexNowIncludesTheType() throws IOException {
        String sql = code();

        assertTrue(sql.contains("DROP INDEX uq_sms_log_delivery_send;"));
        assertTrue(sql.contains("CREATE UNIQUE INDEX uq_sms_log_delivery_type_send ON sms_log "
                + "(delivery_id, message_type, send_number)"));
    }

    @Test
    void aNewMessageIsACodeMessageUnlessSaidOtherwise() {
        assertEquals(SmsMessageType.DELIVERY_CODE, new SmsLog().getMessageType());
    }

    // The SQL without its comment lines.
    private static String code() throws IOException {
        try (InputStream in = SmsMessageTypeMigrationTest.class.getResourceAsStream(
                "/db/migration/V35__sms_message_type.sql")) {
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
