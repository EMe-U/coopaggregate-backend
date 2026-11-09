package com.coopaggregate.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

/** The masked national ID of a member in the list. */
class MemberListItemTest {

    @Test
    void aNationalIdShowsItsLastFourDigitsOnly() {
        assertEquals("••••••••••••5678", MemberListItem.maskNationalId("1199880012345678"));
        assertEquals("••••••••••••5678", MemberListItem.maskNationalId(" 1199880012345678 "));
    }

    @Test
    void aMemberWithoutANationalIdHasNoMask() {
        assertNull(MemberListItem.maskNationalId(null));
        assertNull(MemberListItem.maskNationalId(""));
        assertNull(MemberListItem.maskNationalId("   "));
    }

    @Test
    void anOldMemberWithoutANationalIdIsListedWithNull() {
        assertNull(MemberListItem.from(member(null)).nationalIdMasked());
    }

    @Test
    void theListItemCarriesTheMaskAndNeverTheFullId() {
        MemberListItem item = MemberListItem.from(member("1199880012345678"));

        assertEquals("••••••••••••5678", item.nationalIdMasked());
        assertFalse(item.toString().contains("1199880012345678"));
        assertFalse(item.toString().contains("5678"), "not even the last digits in a log line");
    }

    @Test
    void shortOlderIdsAreMaskedSafely() {
        // Fewer than 8 characters: nothing is shown, and the mask is always 16 characters long.
        for (String shortId : List.of("1", "123", "1234", "12345", "1234567")) {
            String masked = MemberListItem.maskNationalId(shortId);

            assertEquals("••••••••••••••••", masked, shortId);
            assertFalse(masked.chars().anyMatch(Character::isDigit), shortId);
        }
        // From 8 characters, the last 4 are shown, never more.
        assertEquals("••••••••••••5678", MemberListItem.maskNationalId("12345678"));
        assertEquals("••••••••••••-345", MemberListItem.maskNationalId("RW-12-345"));
        assertEquals(16, MemberListItem.maskNationalId("11998800123456789012").length());
    }

    private static Member member(String nationalId) {
        Member member = new Member();
        member.setMemberCode("M-0001");
        member.setFullName("Uwimana Claudine");
        member.setPhone("+250788123456");
        member.setNationalId(nationalId);
        member.setJoinDate(LocalDate.of(2026, 10, 8));
        return member;
    }
}
