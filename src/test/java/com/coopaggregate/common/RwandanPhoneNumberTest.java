package com.coopaggregate.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class RwandanPhoneNumberTest {

    @ParameterizedTest
    @ValueSource(strings = {"0722123456", "0732123456", "0782123456", "0792123456"})
    void acceptsAirtelAndMtnPrefixes(String phone) {
        assertEquals("+250" + phone.substring(1), RwandanPhoneNumber.normalize(phone));
    }

    @Test
    void normalizesEveryAcceptedFormatToTheSameNumber() {
        assertEquals("+250788123456", RwandanPhoneNumber.normalize("0788123456"));
        assertEquals("+250788123456", RwandanPhoneNumber.normalize("250788123456"));
        assertEquals("+250788123456", RwandanPhoneNumber.normalize("+250788123456"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "078812345",       // too short
            "07881234567",     // too long
            "0768123456",      // not a mobile prefix
            "0252123456",      // landline
            "+250 788 123 456",
            "+2560788123456",  // other country
            "788123456"})
    void rejectsInvalidNumbers(String phone) {
        assertFalse(RwandanPhoneNumber.isValid(phone));
        assertThrows(IllegalArgumentException.class, () -> RwandanPhoneNumber.normalize(phone));
    }
}
