package com.coopaggregate.member;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;

import com.coopaggregate.identity.VerificationMethod;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Member {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String memberCode;

    private String fullName;

    private String nationalId;

    private String phone;

    private String address;

    private LocalDate joinDate;

    @Enumerated(EnumType.STRING)
    private MemberStatus status = MemberStatus.ACTIVE;

    private String preferredLanguage = "rw";

    private boolean identityVerified;

    @Enumerated(EnumType.STRING)
    private VerificationMethod verificationMethod;

    private Instant verifiedAt;

    // The identity provider's pseudonymous id for this person, never an ID number. Unique: one person, one member.
    private String identityToken;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public String getMemberCode() {
        return memberCode;
    }

    public void setMemberCode(String memberCode) {
        this.memberCode = memberCode;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getNationalId() {
        return nationalId;
    }

    public void setNationalId(String nationalId) {
        this.nationalId = nationalId;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public LocalDate getJoinDate() {
        return joinDate;
    }

    public void setJoinDate(LocalDate joinDate) {
        this.joinDate = joinDate;
    }

    public MemberStatus getStatus() {
        return status;
    }

    public void setStatus(MemberStatus status) {
        this.status = status;
    }

    public String getPreferredLanguage() {
        return preferredLanguage;
    }

    public void setPreferredLanguage(String preferredLanguage) {
        this.preferredLanguage = preferredLanguage;
    }

    public boolean isIdentityVerified() {
        return identityVerified;
    }

    public VerificationMethod getVerificationMethod() {
        return verificationMethod;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public String getIdentityToken() {
        return identityToken;
    }

    public void markIdentityVerified(VerificationMethod method, Instant verifiedAt, String identityToken) {
        this.identityVerified = true;
        this.verificationMethod = method;
        this.verifiedAt = verifiedAt;
        this.identityToken = identityToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
