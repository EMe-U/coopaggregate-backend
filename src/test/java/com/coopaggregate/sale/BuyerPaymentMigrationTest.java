package com.coopaggregate.sale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.coopaggregate.payment.PaymentMethod;

/** V42 adds how the buyer paid to each order and gives every sale saved without an order an order of its own. */
class BuyerPaymentMigrationTest {

    @Test
    void everyPaymentMethodIsAllowedAndFitsTheColumn() throws IOException {
        String sql = code();

        assertTrue(sql.contains("ALTER TABLE sale_order ADD COLUMN payment_method VARCHAR(10)"));
        assertTrue(sql.contains("CHECK (payment_method IN ('CASH', 'MOMO', 'BANK'))"));
        for (PaymentMethod method : PaymentMethod.values()) {
            assertTrue(sql.contains("'" + method.name() + "'"), method + " is missing from the CHECK");
            assertTrue(method.name().length() <= 10, "the column is VARCHAR(10)");
        }
        // The same limit as the request's @Size(max = 50).
        assertTrue(sql.contains("ALTER TABLE sale_order ADD COLUMN payment_reference VARCHAR(50)"));
    }

    @Test
    void eachSaleWithoutAnOrderGetsItsOwnAndThenEverySaleMustHaveOne() throws IOException {
        String sql = code();

        assertTrue(sql.contains("SELECT 'ORD-S-' || s.id, gen_random_uuid(), s.buyer_id, s.sale_date, s.created_at"));
        assertTrue(sql.contains("WHERE s.sale_order_id IS NULL"));
        assertTrue(sql.contains("AND o.order_code = 'ORD-S-' || s.id"));
        assertTrue(sql.indexOf("SET sale_order_id = o.id") < sql.indexOf("SET NOT NULL"),
                "the link is set before it is required");
        assertTrue(sql.contains("ALTER TABLE sale ALTER COLUMN sale_order_id SET NOT NULL"));
    }

    @Test
    void theBackfilledCodesCannotClashWithRandomOrderCodesAndFitTheColumn() {
        // Random codes are ORD- and 4 characters without a hyphen; ORD-S- and a sale id always has a second hyphen.
        assertFalse("ORD-S-3".matches("ORD-[2-9A-HJKMNP-Z]{4}"));
        assertTrue(("ORD-S-" + 99_999_999_999_999L).length() <= 20, "order_code is VARCHAR(20)");
    }

    @Test
    void noSaleAmountOrLedgerEntryChanges() throws IOException {
        String sql = code().toUpperCase();

        for (String word : new String[] {"DELETE ", "TRUNCATE", "DROP TABLE", "DROP COLUMN", "LEDGER_ENTRY",
                "TOTAL_AMOUNT", "QUANTITY_KG", "PRICE_PER_KG"}) {
            assertFalse(sql.contains(word), word);
        }
        // The only UPDATE sets the order link.
        assertTrue(sql.split("UPDATE ", -1).length - 1 == 1);
    }

    // The SQL without its comment lines.
    private static String code() throws IOException {
        try (InputStream in = BuyerPaymentMigrationTest.class.getResourceAsStream(
                "/db/migration/V42__buyer_payment_on_order.sql")) {
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
