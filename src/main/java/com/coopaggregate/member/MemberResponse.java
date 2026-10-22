package com.coopaggregate.member;

import java.time.Instant;
import java.time.LocalDate;

import com.coopaggregate.identity.VerificationMethod;

public record MemberResponse(
        Long id,
        String memberCode,
        String fullName,
        String phone,
        String nationalId,
        String address,
        LocalDate joinDate,
        MemberStatus status,
        String preferredLanguage,
        Instant createdAt,
        boolean verified,
        VerificationMethod verificationMethod,
        Instant verifiedAt) {

    public static MemberResponse from(Member member) {
        return new MemberResponse(
                member.getId(),
                member.getMemberCode(),
                member.getFullName(),
                member.getPhone(),
                member.getNationalId(),
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
