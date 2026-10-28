package com.coopaggregate.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.coopaggregate.ledger.LedgerEntryType;

/** The V32 migration only adds: the ledger keeps every row, and old sales keep being paid through lot sharing. */
class SaleCreditsMigrationTest {

    @Test
    void noLedgerRowIsChangedOrDeleted() throws IOException {
        String sql = migration().replaceAll("--[^\\n]*", "").toUpperCase();

        assertFalse(sql.contains("UPDATE LEDGER_ENTRY"));
        assertFalse(sql.contains("DELETE FROM"));
        assertFalse(sql.contains("TRUNCATE"));
        assertFalse(sql.contains("DROP TRIGGER"));
        assertFalse(sql.contains("UPDATE SALE_ALLOCATION"), "old allocations must stay uncredited");
        // The only row update fills in the grade of existing sales.
        assertEquals(1, sql.split("\\bUPDATE\\b", -1).length - 1);
        assertTrue(sql.contains("UPDATE SALE S SET GRADE_ID = L.GRADE_ID FROM LOT L WHERE L.ID = S.LOT_ID;"));
    }

    @Test
    void theLedgerAcceptsEveryEntryTypeTheAppWrites() throws IOException {
        String sql = migration();

        assertTrue(sql.contains("ALTER COLUMN entry_type TYPE VARCHAR(20)"));
        for (LedgerEntryType type : LedgerEntryType.values()) {
            assertTrue(type.name().length() <= 20, type.name());
            assertTrue(sql.contains("'" + type.name() + "'"), type + " is missing from the CHECK");
        }
        assertEquals(LedgerEntryType.values().length,
                Arrays.stream(sql.substring(sql.indexOf("CHECK (entry_type IN")).split("\\)")[0].split(","))
                        .count());
    }

    @Test
    void aBatchCanBeCreditedOnlyOnce() throws IOException {
        String sql = migration();

        assertTrue(sql.contains("CREATE UNIQUE INDEX uq_ledger_entry_sale_credit ON ledger_entry "
                + "(related_entity_type, related_entity_id)\n    WHERE entry_type = 'SALE_CREDIT';"));
        assertTrue(sql.contains("(gross_rwf IS NULL AND deduction_rwf IS NULL)"));
        assertTrue(sql.contains("gross_rwf IS NOT NULL AND deduction_rwf IS NOT NULL AND deduction_rwf >= 0 "
                + "AND gross_rwf >= deduction_rwf"));
    }

    @Test
    void oldAllocationsHaveNoCredit() {
        SaleAllocation allocation = new SaleAllocation(null, null, null);

        assertNull(allocation.getGrossRwf());
        assertNull(allocation.getDeductionRwf());
        assertNull(allocation.netRwf(), "kg paid through lot sharing must not count again");
    }

    private static String migration() throws IOException {
        try (InputStream in = SaleCreditsMigrationTest.class
                .getResourceAsStream("/db/migration/V32__sale_credits.sql")) {
            assertNotNull(in, "V32__sale_credits.sql is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
