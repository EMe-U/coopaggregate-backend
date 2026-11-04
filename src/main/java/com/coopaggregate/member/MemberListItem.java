package com.coopaggregate.member;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.coopaggregate.identity.VerificationMethod;

/**
 * A member in a list. The national ID is left out, so it is only shown when one member is opened
 * (MemberResponse); searching by it still works. birthDate, gender, email and verifiedFields are as in MemberResponse.
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
        List<String> verifiedFields) {

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
                member.verifiedFields());
    }

    // Records print their fields; the member's details must never end up in a log line.
    @Override
    public String toString() {
        return "MemberListItem[id=" + id + ", ****]";
    }
}
