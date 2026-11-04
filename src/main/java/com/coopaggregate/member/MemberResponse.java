package com.coopaggregate.member;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.coopaggregate.identity.VerificationMethod;

/**
 * One member. birthDate, gender and email are null when the identity check did not give them; verifiedFields names
 * the details that came from it, out of fullName, phone, birthdate, gender, email and address.
 */
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
        Instant verifiedAt,
        LocalDate birthDate,
        String gender,
        String email,
        List<String> verifiedFields) {

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
                member.getVerifiedAt(),
                member.getBirthDate(),
                member.getGender(),
                member.getEmail(),
                member.verifiedFields());
    }

    // Records print their fields; the member's details must never end up in a log line.
    @Override
    public String toString() {
        return "MemberResponse[id=" + id + ", ****]";
    }
}
