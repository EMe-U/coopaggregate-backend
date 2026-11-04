package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** V34 only adds to the identity tables; the one thing it replaces is the list of allowed methods. */
class IdentityDetailsMigrationTest {

    @Test
    void migrationOnlyAddsColumnsAndWidensTheMethodList() throws IOException {
        String sql = code().toUpperCase();

        assertFalse(sql.contains("UPDATE "));
        assertFalse(sql.contains("DELETE "));
        assertFalse(sql.contains("TRUNCATE"));
        assertFalse(sql.contains("DROP TABLE"));
        assertFalse(sql.contains("DROP COLUMN"));
        assertFalse(sql.contains("ALTER COLUMN"));
        assertEquals(2, count(sql, "DROP CONSTRAINT"), "only the two lists of methods are replaced");
        assertEquals(2, count(sql, "ADD CONSTRAINT"));
    }

    @Test
    void theAllowedMethodsAreExactlyTheValuesOfTheEnum() throws IOException {
        String sql = code();

        assertEquals(2, count(sql, "CHECK (verification_method IN ('OTP', 'FACE', 'PASSWORD', 'MOCK'))"));
        for (VerificationMethod method : VerificationMethod.values()) {
            assertTrue(sql.contains("'" + method.name() + "'"), method + " is missing from the CHECK");
            assertTrue(method.name().length() <= 10, "the column is VARCHAR(10)");
        }
    }

    @Test
    void theNewColumnsHoldNoClaimsOnlySaltedHashes() throws IOException {
        String sql = code();

        for (String column : new String[] {"name_hash", "birth_hash", "phone_hash", "salt"}) {
            assertTrue(sql.contains(column + " ") || sql.contains(column + "  "), column);
        }
        assertFalse(sql.toLowerCase().contains("birth_date"), "the birth date is never stored");
        assertFalse(sql.toLowerCase().contains("full_name"));
        assertFalse(sql.toLowerCase().contains("email"));
        assertTrue(sql.contains("attempts      INTEGER NOT NULL DEFAULT 0"), "old rows start with no attempts");
    }

    @Test
    void usingTheStateClearsTheCodeVerifier() {
        IdentityVerification verification = new IdentityVerification();
        verification.setCodeVerifier("verifier");

        verification.markUsed(Instant.now());

        assertNull(verification.getCodeVerifier());
    }

    @Test
    void theOldColumnsAreNoLongerMapped() {
        java.util.List<String> fields = java.util.Arrays.stream(IdentityVerification.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName).toList();
        for (String old : new String[] {"salt", "nameHash", "birthHash", "phoneHash", "attempts"}) {
            assertFalse(fields.contains(old), old);
        }
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    // The SQL without its comment lines.
    private static String code() throws IOException {
        try (InputStream in = IdentityDetailsMigrationTest.class.getResourceAsStream(
                "/db/migration/V34__identity_verification_details.sql")) {
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
