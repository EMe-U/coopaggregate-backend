package com.coopaggregate.member;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    // Details only the identity check gives (V38); null when eSignet did not release them.
    private LocalDate birthDate;

    private String gender;

    private String email;

    // Which details came from the identity check. The name and phone of a verified member always do (identityVerified).
    private boolean birthDateVerified;

    private boolean genderVerified;

    private boolean emailVerified;

    private boolean addressVerified;

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

    public LocalDate getBirthDate() {
        return birthDate;
    }

    public String getGender() {
        return gender;
    }

    public String getEmail() {
        return email;
    }

    public boolean isBirthDateVerified() {
        return birthDateVerified;
    }

    public boolean isGenderVerified() {
        return genderVerified;
    }

    public boolean isEmailVerified() {
        return emailVerified;
    }

    public boolean isAddressVerified() {
        return addressVerified;
    }

    /** Sets the verified birth date, gender and email, each only when it was released, and marks them verified. */
    public void setVerifiedDetails(LocalDate birthDate, String gender, String email) {
        this.birthDate = birthDate;
        this.birthDateVerified = birthDate != null;
        this.gender = gender;
        this.genderVerified = gender != null;
        this.email = email;
        this.emailVerified = email != null;
    }

    /** Sets the verified address and marks it verified. */
    public void setVerifiedAddress(String address) {
        this.address = address;
        this.addressVerified = address != null;
    }

    /** The address is no longer the verified one (the manager changed it). */
    public void unmarkAddressVerified() {
        this.addressVerified = false;
    }

    /**
     * The names of the details that came from the identity check, out of fullName, phone, birthdate, gender, email
     * and address. Empty for a member who was never verified.
     */
    public List<String> verifiedFields() {
        List<String> fields = new ArrayList<>();
        if (!identityVerified) {
            return List.of();
        }
        fields.add("fullName");
        fields.add("phone");
        if (birthDateVerified) {
            fields.add("birthdate");
        }
        if (genderVerified) {
            fields.add("gender");
        }
        if (emailVerified) {
            fields.add("email");
        }
        if (addressVerified) {
            fields.add("address");
        }
        return List.copyOf(fields);
    }
}
