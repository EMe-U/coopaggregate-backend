package com.coopaggregate.member;

import java.time.Instant;
import java.time.LocalDate;

import com.coopaggregate.identity.VerificationMethod;

/**
 * A member in a list. The national ID is left out, so it is only shown when one member is opened
 * (MemberResponse); searching by it still works.
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
        Instant verifiedAt) {

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
                member.getVerifiedAt());
    }
}
