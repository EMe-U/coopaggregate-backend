package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** V39 only adds one nullable column for the national ID released by the check; it changes no row. */
class VerifiedNationalIdMigrationTest {

    @Test
    void theVerificationGetsANullableNationalIdOfAtMost20Characters() throws IOException {
        assertEquals("ALTER TABLE IDENTITY_VERIFICATION ADD COLUMN VERIFIED_NATIONAL_ID VARCHAR(20);",
                code().toUpperCase().trim());
    }

    @Test
    void nothingIsChangedDroppedOrRequired() throws IOException {
        String sql = code().toUpperCase();

        for (String word : new String[] {"DROP ", "DELETE ", "UPDATE ", "TRUNCATE", "NOT NULL", "MEMBER "}) {
            assertFalse(sql.contains(word), word);
        }
    }

    // The SQL without its comment lines.
    private static String code() throws IOException {
        try (InputStream in = VerifiedNationalIdMigrationTest.class.getResourceAsStream(
                "/db/migration/V39__verified_national_id.sql")) {
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
