package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ClaimFingerprintsTest {

    // --- Names

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Uwimana Claudine|claudine uwimana",
            "  UWIMANA   claudine  |claudine uwimana",
            "Claudine Uwimana|claudine uwimana",
            "Uwimana, Claudine|claudine uwimana",
            "Uwimana-Claudine|claudine uwimana",
            "Ûwimana Claudiné|claudine uwimana",
            "Mukamana Jeanne d'Arc|arc d jeanne mukamana"})
    void namesAreComparedWithoutCaseAccentsPunctuationOrWordOrder(String name, String expected) {
        assertEquals(expected, ClaimFingerprints.normalizeName(name));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "-.,"})
    void aNameWithNothingInItIsMissing(String name) {
        assertNull(ClaimFingerprints.normalizeName(name));
    }

    @Test
    void aDifferentNameIsADifferentName() {
        assertNotEquals(ClaimFingerprints.normalizeName("Uwimana Claudine"),
                ClaimFingerprints.normalizeName("Uwimana Claudette"));
        assertNotEquals(ClaimFingerprints.normalizeName("Uwimana Claudine"),
                ClaimFingerprints.normalizeName("Uwimana"), "a missing word is a mismatch, not a match");
    }

    // --- Birth dates

    @ParameterizedTest
    @CsvSource({"1990/05/17,1990-05-17", "1990-05-17,1990-05-17", " 2001/12/01 ,2001-12-01", "1999-02-28,1999-02-28"})
    void birthDatesAreReadFromBothFormats(String raw, String expected) {
        assertEquals(expected, ClaimFingerprints.normalizeBirth(raw));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "17/05/1990", "1990/13/01", "1990/02/30", "1990-5-7", "yesterday", "19900517"})
    void aBirthDateInAnotherFormatOrThatDoesNotExistIsMissing(String raw) {
        assertNull(ClaimFingerprints.normalizeBirth(raw));
    }

    @Test
    void theDateOfTheFormIsTheSameKindOfValue() {
        assertEquals(ClaimFingerprints.normalizeBirth("1990/05/17"),
                ClaimFingerprints.normalizeBirth(LocalDate.of(1990, 5, 17)));
        assertNull(ClaimFingerprints.normalizeBirth((LocalDate) null));
    }

    // --- Phones

    @ParameterizedTest
    @ValueSource(strings = {"0788123456", "250788123456", "+250788123456", " +250788123456 "})
    void phonesAreComparedInTheStoredFormat(String raw) {
        assertEquals("+250788123456", ClaimFingerprints.normalizePhone(raw));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"12345", "0711111111", "+254712345678", "abc"})
    void aPhoneThatIsNotARwandanMobileNumberIsMissing(String raw) {
        assertNull(ClaimFingerprints.normalizePhone(raw));
    }

    // --- Hashes

    @Test
    void theSameDetailWithTheSameSaltGivesTheSameHash() {
        String salt = ClaimFingerprints.newSalt();

        assertEquals(ClaimFingerprints.hash(salt, ClaimFingerprints.NAME, "claudine uwimana"),
                ClaimFingerprints.hash(salt, ClaimFingerprints.NAME, "claudine uwimana"));
        assertTrue(ClaimFingerprints.same(ClaimFingerprints.hash(salt, ClaimFingerprints.NAME, "a"),
                ClaimFingerprints.hash(salt, ClaimFingerprints.NAME, "a")));
    }

    @Test
    void anotherSaltAnotherDetailOrAnotherKindGivesAnotherHash() {
        String salt = ClaimFingerprints.newSalt();
        String base = ClaimFingerprints.hash(salt, ClaimFingerprints.NAME, "claudine uwimana");

        assertNotEquals(base, ClaimFingerprints.hash(ClaimFingerprints.newSalt(), ClaimFingerprints.NAME,
                "claudine uwimana"));
        assertNotEquals(base, ClaimFingerprints.hash(salt, ClaimFingerprints.NAME, "claudine uwimanb"));
        assertNotEquals(base, ClaimFingerprints.hash(salt, ClaimFingerprints.PHONE, "claudine uwimana"));
        assertFalse(ClaimFingerprints.same(base, ClaimFingerprints.hash(salt, ClaimFingerprints.NAME, "x")));
    }

    @Test
    void aHashIs64HexCharactersAndHoldsNoPartOfTheDetail() {
        String salt = ClaimFingerprints.newSalt();
        String hash = ClaimFingerprints.hash(salt, ClaimFingerprints.PHONE, "+250788123456");

        assertTrue(hash.matches("[0-9a-f]{64}"), hash);
        assertTrue(salt.matches("[0-9a-f]{32}"), salt);
        assertFalse(hash.contains("788123456"));
    }

    @Test
    void aMissingHashNeverMatches() {
        assertFalse(ClaimFingerprints.same(null, null));
        assertFalse(ClaimFingerprints.same("abc", null));
        assertFalse(ClaimFingerprints.same(null, "abc"));
    }
}
