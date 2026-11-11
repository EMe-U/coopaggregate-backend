package com.coopaggregate.lot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** V40 only adds EMPTIED to the allowed lot statuses; it changes no row. */
class LotEmptiedStatusMigrationTest {

    @Test
    void everyLotStatusIsAllowedAndFitsTheColumn() throws IOException {
        String sql = code();

        assertTrue(sql.contains(
                "CHECK (status IN ('OPEN', 'CLOSED', 'SOLD', 'SHARED', 'FILLING', 'FULL', 'EMPTIED'))"));
        for (LotStatus status : LotStatus.values()) {
            assertTrue(sql.contains("'" + status.name() + "'"), status + " is missing from the CHECK");
            assertTrue(status.name().length() <= 10, "the column is VARCHAR(10)");
        }
    }

    @Test
    void onlyTheStatusCheckIsReplacedAndNoRowIsChanged() throws IOException {
        String sql = code().toUpperCase();

        assertEquals(1, sql.split("DROP CONSTRAINT LOT_STATUS_CHECK", -1).length - 1);
        for (String word : new String[] {"UPDATE ", "DELETE ", "INSERT ", "TRUNCATE", "DROP TABLE", "DROP COLUMN",
                "LEDGER_ENTRY"}) {
            assertFalse(sql.contains(word), word);
        }
    }

    // The SQL without its comment lines.
    private static String code() throws IOException {
        try (InputStream in = LotEmptiedStatusMigrationTest.class.getResourceAsStream(
                "/db/migration/V40__lot_emptied_status.sql")) {
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
