package com.coopaggregate.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** How each optional detail of eSignet's userinfo answer is read. */
class ClaimValuesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 4);

    // --- birthdate

    @Test
    void theBirthDateIsReadInEsignetsFormatAndInTheIsoFormat() {
        assertEquals(LocalDate.of(1990, 5, 17), ClaimValues.birthdate("1990/05/17", TODAY));
        assertEquals(LocalDate.of(1990, 5, 17), ClaimValues.birthdate("1990-05-17", TODAY));
        assertEquals(LocalDate.of(1990, 5, 17), ClaimValues.birthdate(" 1990/05/17 ", TODAY));
    }

    @Test
    void anInvalidBirthDateIsIgnored() {
        for (String value : List.of("1990/02/30", "2001/02/29", "1990/5/17", "17/05/1990", "1990.05.17", "19900517",
                "1990/05/17T00:00", "1990", "", "not a date")) {
            assertNull(ClaimValues.birthdate(value, TODAY), value);
        }
        assertNull(ClaimValues.birthdate(19900517, TODAY), "a number");
        assertNull(ClaimValues.birthdate(null, TODAY));
    }

    @Test
    void aBirthDateInTheFutureOrBefore1900IsIgnored() {
        assertNull(ClaimValues.birthdate("2026/11/05", TODAY));
        assertNull(ClaimValues.birthdate("1899/12/31", TODAY));
        assertEquals(TODAY, ClaimValues.birthdate("2026/11/04", TODAY));
        assertEquals(LocalDate.of(1900, 1, 1), ClaimValues.birthdate("1900/01/01", TODAY));
    }

    // --- gender

    @Test
    void theGenderIsKeptAsGivenTrimmed() {
        assertEquals("Female", ClaimValues.gender("  Female "));
        assertEquals("Other", ClaimValues.gender("Other"));
    }

    @Test
    void aGenderLongerThan20CharactersOrNotTextIsIgnored() {
        assertEquals("x".repeat(20), ClaimValues.gender("x".repeat(20)));
        assertNull(ClaimValues.gender("x".repeat(21)));
        assertNull(ClaimValues.gender(Map.of("value", "Female")));
        assertNull(ClaimValues.gender("   "));
    }

    // --- email

    @Test
    void theEmailIsKeptTrimmed() {
        assertEquals("claudine@example.com", ClaimValues.email(" claudine@example.com "));
    }

    @Test
    void somethingThatIsClearlyNotAnEmailOrTooLongIsIgnored() {
        assertNull(ClaimValues.email("claudine.example.com"));
        assertNull(ClaimValues.email(""));
        String longest = "a".repeat(242) + "@example.com";
        assertEquals(254, longest.length());
        assertEquals(longest, ClaimValues.email(longest));
        assertNull(ClaimValues.email("a" + longest));
    }

    // --- address

    @Test
    void anAddressObjectBecomesOneLineInTheOrderStreetLocalityRegionCountry() {
        Map<String, Object> address = new HashMap<>();
        address.put("country", "Rwanda");
        address.put("region", "Gasabo");
        address.put("locality", "Kacyiru");
        address.put("street_address", "KG 7 Ave");
        address.put("postal_code", "00000");
        address.put("formatted", "ignored");

        assertEquals("KG 7 Ave, Kacyiru, Gasabo, Rwanda", ClaimValues.address(address));
    }

    @Test
    void missingOrEmptyPartsOfTheAddressAreLeftOut() {
        Map<String, Object> address = new HashMap<>();
        address.put("street_address", " ");
        address.put("locality", "Kacyiru");
        address.put("region", null);
        address.put("country", "Rwanda");

        assertEquals("Kacyiru, Rwanda", ClaimValues.address(address));
        assertNull(ClaimValues.address(Map.of("postal_code", "00000")));
        assertNull(ClaimValues.address(Map.of()));
    }

    @Test
    void aPlainStringAddressIsKeptAsGiven() {
        assertEquals("KG 7 Ave, Kacyiru", ClaimValues.address("  KG 7 Ave,  Kacyiru "));
    }

    @Test
    void aLongAddressIsCutCleanlyAt500Characters() {
        String longer = "Street ".repeat(100);
        String cut = ClaimValues.address(longer);
        assertTrue(cut.length() <= 500, String.valueOf(cut.length()));
        assertTrue(cut.endsWith("Street"), cut.substring(cut.length() - 10));

        String withCommas = "a".repeat(498) + ", " + "b".repeat(10);
        assertEquals("a".repeat(498), ClaimValues.address(withCommas), "no dangling comma");

        String surrogate = "a".repeat(499) + "😀" + "b";
        String cutSurrogate = ClaimValues.address(surrogate);
        assertEquals("a".repeat(499), cutSurrogate, "never half of a character");

        assertEquals("a".repeat(500), ClaimValues.address("a".repeat(500)));
    }

    // --- every text

    @Test
    void controlCharactersAreRemovedFromEveryValue() {
        assertEquals("Female", ClaimValues.gender("Female\u0007"));
        assertEquals("KG 7 Ave, Kacyiru", ClaimValues.address(Map.of("street_address", "KG 7\nAve",
                "locality", "Kacyiru\r\n\t")));
        assertEquals("claudine@example.com", ClaimValues.email("\u001Bclaudine@example.com"));
        assertEquals("Uwimana Claudine", ClaimValues.text("Uwimana\u0000Claudine"));
        assertNull(ClaimValues.text("\u0000\u0001"));
    }
}
