package com.coopaggregate.payment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.coopaggregate.error.BadRequestException;

class PaymentReferencesTest {

    private static final String MISSING = "A MOMO payment needs the transaction reference.";

    @ParameterizedTest
    @ValueSource(strings = {"MP2610", "MP2610120001", "BK/2026.11-07", "a.b-c/1", "  MP2610120001  "})
    void aWellFormedReferenceIsKeptTrimmed(String reference) {
        assertEquals(reference.trim(), PaymentReferences.checked(PaymentMethod.MOMO, reference, MISSING));
    }

    @Test
    void sixAndFortyCharactersAreTheLimits() {
        assertEquals("123456", PaymentReferences.checked(PaymentMethod.BANK, "123456", MISSING));
        assertEquals("x".repeat(40), PaymentReferences.checked(PaymentMethod.BANK, "x".repeat(40), MISSING));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "  12345  ", "MP 2610120001", "MP_2610120001", "MP#2610", "MP2610+1", "RéF2610",
            "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void anythingElseIsRefusedWithTheRule(String reference) {
        for (PaymentMethod method : new PaymentMethod[] {PaymentMethod.MOMO, PaymentMethod.BANK}) {
            BadRequestException e = assertThrows(BadRequestException.class,
                    () -> PaymentReferences.checked(method, reference, MISSING));

            assertEquals("The " + method + " reference must be 6 to 40 characters: letters, digits, dot (.), "
                    + "dash (-) or slash (/) only.", e.getMessage());
        }
    }

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = {"MOMO", "BANK"})
    void aMissingReferenceGetsTheCallersMessage(PaymentMethod method) {
        for (String reference : new String[] {null, "", "   "}) {
            assertEquals(MISSING, assertThrows(BadRequestException.class,
                    () -> PaymentReferences.checked(method, reference, MISSING)).getMessage());
        }
    }

    @Test
    void aCashReferenceStaysOptionalAndFree() {
        assertNull(PaymentReferences.checked(PaymentMethod.CASH, null, MISSING));
        assertNull(PaymentReferences.checked(PaymentMethod.CASH, "  ", MISSING));
        assertEquals("receipt #12", PaymentReferences.checked(PaymentMethod.CASH, " receipt #12 ", MISSING));
        assertEquals("1", PaymentReferences.checked(PaymentMethod.CASH, "1", MISSING));
        assertFalse(PaymentReferences.mustBeUnique(PaymentMethod.CASH));
        assertTrue(PaymentReferences.mustBeUnique(PaymentMethod.MOMO));
        assertTrue(PaymentReferences.mustBeUnique(PaymentMethod.BANK));
    }

    @Test
    void theLockKeyIgnoresCaseButNotTheMethod() {
        assertEquals(PaymentReferences.lockKey(PaymentMethod.MOMO, "mp2610"),
                PaymentReferences.lockKey(PaymentMethod.MOMO, "MP2610"));
        assertFalse(PaymentReferences.lockKey(PaymentMethod.MOMO, "MP2610")
                .equals(PaymentReferences.lockKey(PaymentMethod.BANK, "MP2610")));
    }

    @Test
    void theDuplicateMessageNamesTheRecordThatUsedIt() {
        assertEquals("Reference MP2610 was already used for PAY-0002.",
                PaymentReferences.alreadyUsed("MP2610", "PAY-0002").getMessage());
    }
}
