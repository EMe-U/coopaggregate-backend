package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** V38 only adds columns (and widens member.address); it changes no row and drops nothing. */
class AllVerifiedDetailsMigrationTest {

    @Test
    void theVerificationGetsTheFourOptionalDetails() throws IOException {
        String sql = code().toUpperCase();

        assertTrue(sql.contains("ADD COLUMN VERIFIED_BIRTHDATE DATE"));
        assertTrue(sql.contains("ADD COLUMN VERIFIED_GENDER    VARCHAR(20)"));
        assertTrue(sql.contains("ADD COLUMN VERIFIED_EMAIL     VARCHAR(254)"));
        assertTrue(sql.contains("ADD COLUMN VERIFIED_ADDRESS   VARCHAR(500)"));
    }

    @Test
    void theMemberGetsTheDetailsAndOneFlagEachDefaultingToFalse() throws IOException {
        String sql = code().toUpperCase();

        assertTrue(sql.contains("ADD COLUMN BIRTH_DATE          DATE,"));
        assertTrue(sql.contains("ADD COLUMN GENDER              VARCHAR(20),"));
        assertTrue(sql.contains("ADD COLUMN EMAIL               VARCHAR(254),"));
        for (String flag : new String[] {"BIRTH_DATE_VERIFIED", "GENDER_VERIFIED", "EMAIL_VERIFIED",
                "ADDRESS_VERIFIED"}) {
            assertTrue(sql.matches("(?s).*ADD COLUMN " + flag + " +BOOLEAN NOT NULL DEFAULT FALSE.*"), flag);
        }
        assertFalse(sql.contains("ADD COLUMN ADDRESS "), "the existing address column is reused");
    }

    @Test
    void nothingIsChangedDroppedOrNarrowed() throws IOException {
        String sql = code().toUpperCase();

        assertFalse(sql.contains("DROP "));
        assertFalse(sql.contains("DELETE "));
        assertFalse(sql.contains("UPDATE "));
        assertFalse(sql.contains("TRUNCATE"));
        assertFalse(sql.contains("PICTURE"));
        // The only change to an existing column: member.address made longer (255 to 500), which keeps every value.
        assertEquals(1, sql.split("ALTER COLUMN", -1).length - 1);
        assertTrue(sql.contains("ALTER COLUMN ADDRESS TYPE VARCHAR(500)"));
        // Every added column is nullable or has a default, so existing rows need nothing.
        for (String line : sql.split("\n")) {
            if (line.contains("ADD COLUMN") && line.contains("NOT NULL")) {
                assertTrue(line.contains("DEFAULT"), line);
            }
        }
    }

    // The SQL without its comment lines.
    private static String code() throws IOException {
        try (InputStream in = AllVerifiedDetailsMigrationTest.class.getResourceAsStream(
                "/db/migration/V38__all_verified_details.sql")) {
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
