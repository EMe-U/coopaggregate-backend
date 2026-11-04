package com.coopaggregate.buyer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** V33 only adds: existing requests become CALL, and nothing that exists is changed or dropped. */
class BuyerRequestContactMigrationTest {

    @Test
    void migrationOnlyAddsColumnsAndConstraintsToBuyerRequest() throws IOException {
        String sql = code().toUpperCase();

        assertFalse(sql.contains("UPDATE "), "existing rows get their value from the column default");
        assertFalse(sql.contains("DELETE "));
        assertFalse(sql.contains("DROP "));
        assertFalse(sql.contains("TRUNCATE"));
        assertFalse(sql.contains("ALTER COLUMN"), "no existing column is changed");
        assertEquals(3, sql.split("ALTER TABLE", -1).length - 1);
        assertEquals(3, sql.split("ALTER TABLE BUYER_REQUEST", -1).length - 1, "only buyer_request is touched");
    }

    @Test
    void existingRequestsBecomeCallWithoutAWhatsappNumber() throws IOException {
        String sql = code();

        assertTrue(sql.contains("contact_method  VARCHAR(10) NOT NULL DEFAULT 'CALL'"));
        // The number has no default and may be empty, so old rows (CALL, no number) satisfy both constraints.
        assertTrue(sql.contains("whatsapp_number VARCHAR(20);"));
        assertTrue(sql.contains("(contact_method = 'WHATSAPP' AND whatsapp_number IS NOT NULL)"));
        assertTrue(sql.contains("OR (contact_method = 'CALL' AND whatsapp_number IS NULL)"));
    }

    @Test
    void theDatabaseAcceptsExactlyTheValuesOfTheEnum() throws IOException {
        String sql = code();

        for (BuyerContactMethod method : BuyerContactMethod.values()) {
            assertTrue(sql.contains("'" + method.name() + "'"), method + " is missing from the CHECK");
            assertTrue(method.name().length() <= 10, "the column is VARCHAR(10)");
        }
        assertTrue(sql.contains("CHECK (contact_method IN ('CALL', 'WHATSAPP'))"));
    }

    @Test
    void newRequestIsCallByDefaultAndTheEntityStoresTheEnumAsText() throws Exception {
        BuyerRequest request = new BuyerRequest();

        assertEquals(BuyerContactMethod.CALL, request.getContactMethod());
        assertNull(request.getWhatsappNumber());
        Field field = BuyerRequest.class.getDeclaredField("contactMethod");
        assertEquals(EnumType.STRING, field.getAnnotation(Enumerated.class).value());
    }

    private static String code() throws IOException {
        try (InputStream in = BuyerRequestContactMigrationTest.class
                .getResourceAsStream("/db/migration/V33__buyer_request_contact_method.sql")) {
            assertNotNull(in, "V33__buyer_request_contact_method.sql is missing");
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            return sql.replaceAll("--[^\\n]*", "");
        }
    }
}
