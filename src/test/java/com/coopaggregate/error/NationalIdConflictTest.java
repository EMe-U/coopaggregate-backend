package com.coopaggregate.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** Two registrations with the same national ID at once: the database refuses the second one. */
@ExtendWith(OutputCaptureExtension.class)
class NationalIdConflictTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void theDatabaseRefusingADuplicateNationalIdGivesNationalIdTakenAndNeverLogsTheId(CapturedOutput output) {
        // What Postgres says when the error details are not switched off.
        SQLException cause = new SQLException("ERROR: duplicate key value violates unique constraint "
                + "\"member_national_id_key\"\n  Detail: Key (national_id)=(1199880012345678) already exists.");

        ApiError error = handler.handleDataConflict(new DataIntegrityViolationException("could not execute", cause));

        assertEquals("NATIONAL_ID_TAKEN", error.code());
        assertEquals("This national ID is already used by another member.", error.message());
        assertNull(error.memberCode());
        assertTrue(output.getAll().contains("national ID already used (constraint member_national_id_key)"));
        assertFalse(output.getAll().contains("1199880012345678"));
    }

    @Test
    void anyOtherConflictIsAnsweredAsBefore() {
        ApiError error = handler.handleDataConflict(new DataIntegrityViolationException("could not execute",
                new SQLException("ERROR: duplicate key value violates unique constraint \"uq_member_phone\"")));

        assertEquals("The request conflicts with existing data.", error.message());
        assertNull(error.code());
    }
}
