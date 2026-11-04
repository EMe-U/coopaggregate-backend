package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/** V37 only adds the two verified columns and empties the old fingerprints; nothing is dropped or narrowed. */
class VerifiedDetailsMigrationTest {

    @Test
    void migrationOnlyAddsTheVerifiedColumnsAndEmptiesTheOldOnes() throws IOException {
        String sql = code().toUpperCase();

        assertTrue(sql.contains("ADD COLUMN VERIFIED_NAME  VARCHAR(150)"));
        assertTrue(sql.contains("ADD COLUMN VERIFIED_PHONE VARCHAR(13)"));
        assertFalse(sql.contains("DROP "));
        assertFalse(sql.contains("DELETE "));
        assertFalse(sql.contains("TRUNCATE"));
        assertFalse(sql.contains("ALTER COLUMN"));
        assertEquals(1, count(sql, "UPDATE "), "only the old fingerprints are emptied");
        assertTrue(sql.contains("SET SALT = NULL, NAME_HASH = NULL, BIRTH_HASH = NULL, PHONE_HASH = NULL, ATTEMPTS = 0"));
        assertFalse(sql.contains("BIRTH_DATE"), "the birth date is never stored");
        assertFalse(sql.contains("EMAIL"));
    }

    @Test
    void theNameAndPhoneAreSetAndClearedTogether() throws IOException {
        assertTrue(code().contains("CHECK ((verified_name IS NULL) = (verified_phone IS NULL))"));

        IdentityVerification verification = new IdentityVerification();
        verification.setVerifiedDetails("UWIMANA Claudine", "+250788123456");
        assertTrue(verification.hasVerifiedDetails());
        verification.clearVerifiedDetails();
        assertNull(verification.getVerifiedName());
        assertNull(verification.getVerifiedPhone());
        assertFalse(verification.hasVerifiedDetails());
    }

    @Test
    void theRowNeverPrintsTheVerifiedDetails() {
        IdentityVerification verification = new IdentityVerification();
        verification.setVerifiedDetails("UWIMANA Claudine", "+250788123456");

        assertFalse(verification.toString().contains("UWIMANA"));
        assertFalse(verification.toString().contains("788123456"));
    }

    @Test
    void theCleanupRunsEveryMinuteByDefault() throws NoSuchMethodException {
        Scheduled schedule = IdentityCleanupJob.class.getMethod("run").getAnnotation(Scheduled.class);

        assertEquals("${app.identity.cleanup-delay-ms:60000}", schedule.fixedDelayString());
    }

    private static int count(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }

    // The SQL without its comment lines.
    private static String code() throws IOException {
        try (InputStream in = VerifiedDetailsMigrationTest.class.getResourceAsStream(
                "/db/migration/V37__verified_member_details.sql")) {
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
