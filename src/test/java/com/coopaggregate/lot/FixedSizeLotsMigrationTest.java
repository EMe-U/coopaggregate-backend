package com.coopaggregate.lot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** V36 only adds: no ledger row, lot row or allocation row is changed; the one update fills in each delivery's grade. */
class FixedSizeLotsMigrationTest {

    @Test
    void theLedgerIsNeverTouched() throws IOException {
        assertFalse(code().toLowerCase().contains("ledger_entry"));
    }

    @Test
    void theOnlyRowUpdateFillsInTheDeliveryGrade() throws IOException {
        String sql = code().toUpperCase();

        assertEquals(1, count(sql, "UPDATE "));
        assertTrue(sql.contains("UPDATE DELIVERY D SET GRADE_ID = L.GRADE_ID FROM LOT L WHERE L.ID = D.LOT_ID;"));
        assertFalse(sql.contains("DELETE "));
        assertFalse(sql.contains("TRUNCATE"));
        assertFalse(sql.contains("DROP TABLE"));
        assertFalse(sql.contains("DROP COLUMN"));
    }

    @Test
    void existingLotsKeepTheirStatusesAndOnlyNewOnesAreAdded() throws IOException {
        String sql = code();

        assertTrue(sql.contains("CHECK (status IN ('OPEN', 'CLOSED', 'SOLD', 'SHARED', 'FILLING', 'FULL'))"));
        for (String status : new String[] {"OPEN", "CLOSED", "SOLD", "SHARED", "FILLING", "FULL"}) {
            assertTrue(sql.contains("'" + status + "'"), status + " is missing from the CHECK");
        }
        assertTrue(sql.contains("CREATE UNIQUE INDEX uq_lot_one_filling_per_grade ON lot (grade_id) WHERE status = 'FILLING';"));
        assertTrue(sql.contains("CHECK (status NOT IN ('FILLING', 'FULL') OR capacity_kg IS NOT NULL)"));
    }

    @Test
    void theLotSizeDefaultsTo100KgAndMustBePositive() throws IOException {
        String sql = code();

        assertTrue(sql.contains("ADD COLUMN lot_size_kg NUMERIC(12,2) NOT NULL DEFAULT 100;"));
        assertTrue(sql.contains("CHECK (lot_size_kg > 0)"));
    }

    @Test
    void everyExistingDeliveryBecomesOnePortionOfItsLotAndTheResultIsChecked() throws IOException {
        String sql = code();

        assertTrue(sql.contains("INSERT INTO lot_portion (lot_id, delivery_id, quantity_kg, sold_kg, lost_kg, created_at)"));
        assertTrue(sql.contains("SELECT d.lot_id, d.id, d.quantity_kg, d.sold_kg, d.lost_kg, d.created_at"));
        assertTrue(sql.contains("RAISE EXCEPTION 'Fixed-size lot backfill does not match"));
    }

    @Test
    void aSaleIsUniquePerPortionAndOlderAllocationsStayUniquePerDelivery() throws IOException {
        String sql = code();

        assertTrue(sql.contains("ON sale_allocation (sale_id, delivery_id) WHERE lot_portion_id IS NULL;"));
        assertTrue(sql.contains("ON sale_allocation (sale_id, lot_portion_id)\n    WHERE lot_portion_id IS NOT NULL;"));
        assertFalse(sql.toUpperCase().contains("UPDATE SALE_ALLOCATION"));
        assertFalse(sql.toUpperCase().contains("UPDATE LOSS_ALLOCATION"));
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    // The SQL without its comment lines.
    private static String code() throws IOException {
        try (InputStream in = FixedSizeLotsMigrationTest.class.getResourceAsStream(
                "/db/migration/V36__fixed_size_lots.sql")) {
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
