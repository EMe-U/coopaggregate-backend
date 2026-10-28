package com.coopaggregate.batch;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.junit.jupiter.api.Test;

import com.coopaggregate.delivery.Delivery;

import jakarta.persistence.Column;

/** The batch columns on Delivery, and the safety checks in the V31 migration. */
class BatchMappingTest {

    @Test
    void remainingKgIsReadOnlyAndReadBackFromTheDatabase() throws Exception {
        Field remaining = Delivery.class.getDeclaredField("remainingKg");

        Column column = remaining.getAnnotation(Column.class);
        assertNotNull(column);
        assertFalse(column.insertable(), "the database computes remaining_kg; Hibernate must not insert it");
        assertFalse(column.updatable(), "the database computes remaining_kg; Hibernate must not update it");
        Generated generated = remaining.getAnnotation(Generated.class);
        assertNotNull(generated, "remaining_kg must be read back after Hibernate writes the row");
        assertArrayEquals(new EventType[] {EventType.INSERT, EventType.UPDATE}, generated.event());
    }

    @Test
    void soldAndLostKgAreWrittenByTheApp() throws Exception {
        for (String name : new String[] {"soldKg", "lostKg"}) {
            Column column = Delivery.class.getDeclaredField(name).getAnnotation(Column.class);
            assertTrue(column == null || (column.insertable() && column.updatable()), name);
            assertNull(Delivery.class.getDeclaredField(name).getAnnotation(Generated.class), name);
        }
    }

    @Test
    void newDeliveryHasNothingSoldOrLostAndAllItsKgLeft() {
        Delivery delivery = new Delivery();
        delivery.setQuantityKg(new BigDecimal("120.50"));

        assertEquals(BigDecimal.ZERO, delivery.getSoldKg());
        assertEquals(BigDecimal.ZERO, delivery.getLostKg());
        assertEquals(new BigDecimal("120.50"), delivery.unallocatedKg());
        assertNull(delivery.getRemainingKg(), "only the database fills remainingKg");
    }

    @Test
    void soldAndLostKgReduceWhatIsLeft() {
        Delivery delivery = new Delivery();
        delivery.setQuantityKg(new BigDecimal("120.50"));
        delivery.addSoldKg(new BigDecimal("100.00"));
        delivery.addLostKg(new BigDecimal("0.50"));

        assertEquals(new BigDecimal("20.00"), delivery.unallocatedKg());
        assertEquals(new BigDecimal("0.50"), delivery.releaseLostKg());
        assertEquals(BigDecimal.ZERO, delivery.getLostKg());
    }

    @Test
    void migrationChecksTheBackfillAndRollsBackOnAnyDifference() throws IOException {
        String sql = migration();

        assertTrue(sql.contains("GENERATED ALWAYS AS (quantity_kg - sold_kg - lost_kg) STORED"));
        assertTrue(sql.contains("CHECK (sold_kg >= 0 AND lost_kg >= 0 AND sold_kg + lost_kg <= quantity_kg)"));
        // Reversed deliveries are not batches and get no kg.
        assertTrue(sql.contains("FROM delivery d\nWHERE d.reversed_at IS NULL"));
        // Oldest first: by delivered time, then id. Sales and losses in the order they were recorded.
        assertTrue(sql.contains("ORDER BY d.delivered_at, d.id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW"));
        assertTrue(sql.contains("ORDER BY created_at, kind, event_id ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW"));
        // Totals before and after are compared; any difference stops the migration.
        assertTrue(sql.contains("CREATE TEMP TABLE backfill_lot_totals"));
        assertTrue(sql.contains("COALESCE(b.remaining, 0) <> t.delivered_kg - t.lost_kg - t.sold_kg"));
        assertTrue(sql.contains("RAISE EXCEPTION 'Batch backfill does not match"));
        // Only new things: no existing row of sale, loss, lot or the ledger is changed or deleted.
        assertFalse(sql.contains("UPDATE sale"));
        assertFalse(sql.contains("UPDATE loss"));
        assertFalse(sql.contains("UPDATE lot"));
        assertFalse(sql.toLowerCase().contains("ledger_entry"));
        assertFalse(sql.toUpperCase().contains("DELETE FROM"));
    }

    private static String migration() throws IOException {
        try (InputStream in = BatchMappingTest.class.getResourceAsStream("/db/migration/V31__delivery_batches.sql")) {
            assertNotNull(in, "V31__delivery_batches.sql is missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }
}
