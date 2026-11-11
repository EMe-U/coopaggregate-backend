package com.coopaggregate.member;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.coopaggregate.identity.VerificationMethod;

/**
 * A member in a list. The full national ID is left out, so it is only shown when one member is opened
 * (MemberResponse); searching by it still works. nationalIdMasked shows its last 4 digits only. birthDate, gender,
 * email and verifiedFields are as in MemberResponse.
 */
public record MemberListItem(
        Long id,
        String memberCode,
        String fullName,
        String phone,
        String address,
        LocalDate joinDate,
        MemberStatus status,
        String preferredLanguage,
        Instant createdAt,
        boolean verified,
        VerificationMethod verificationMethod,
        Instant verifiedAt,
        LocalDate birthDate,
        String gender,
        String email,
        List<String> verifiedFields,
        String nationalIdMasked) {

    private static final String DOT = "•";
    // Always 16 characters, like a real ID, so the mask never shows how long an older ID is.
    private static final int MASK_LENGTH = 16;
    private static final int SHOWN = 4;
    // An ID shorter than this shows nothing: 4 visible characters would be too much of it.
    private static final int SHORTEST_PARTLY_SHOWN = 8;

    /**
     * "••••••••••••5678" for 1199880012345678: the last 4 characters only. null when the member has no national ID.
     * An older ID shorter than 8 characters is shown as dots only.
     */
    public static String maskNationalId(String nationalId) {
        if (nationalId == null || nationalId.isBlank()) {
            return null;
        }
        String id = nationalId.strip();
        if (id.length() < SHORTEST_PARTLY_SHOWN) {
            return DOT.repeat(MASK_LENGTH);
        }
        return DOT.repeat(MASK_LENGTH - SHOWN) + id.substring(id.length() - SHOWN);
    }

    public static MemberListItem from(Member member) {
        return new MemberListItem(
                member.getId(),
                member.getMemberCode(),
                member.getFullName(),
                member.getPhone(),
                member.getAddress(),
                member.getJoinDate(),
                member.getStatus(),
                member.getPreferredLanguage(),
                member.getCreatedAt(),
                member.isIdentityVerified(),
                member.getVerificationMethod(),
                member.getVerifiedAt(),
                member.getBirthDate(),
                member.getGender(),
                member.getEmail(),
                member.verifiedFields(),
                maskNationalId(member.getNationalId()));
    }

    // Records print their fields; the member's details must never end up in a log line.
    @Override
    public String toString() {
        return "MemberListItem[id=" + id + ", ****]";
    }
}
