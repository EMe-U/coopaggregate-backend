package com.coopaggregate.member;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;

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

    private String pinHash;

    private Instant pinSetAt;

    private int pinFailedAttempts;

    private Instant pinLockedAt;

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

    public boolean hasPin() {
        return pinHash != null;
    }

    public boolean isPinLocked() {
        return pinLockedAt != null;
    }

    public String getPinHash() {
        return pinHash;
    }

    public Instant getPinSetAt() {
        return pinSetAt;
    }

    public int getPinFailedAttempts() {
        return pinFailedAttempts;
    }

    public Instant getPinLockedAt() {
        return pinLockedAt;
    }

    /** Sets a new PIN hash and clears any earlier failed attempts and lock. */
    public void changePin(String newPinHash, Instant now) {
        this.pinHash = newPinHash;
        this.pinSetAt = now;
        this.pinFailedAttempts = 0;
        this.pinLockedAt = null;
    }

    public void recordPinSuccess() {
        this.pinFailedAttempts = 0;
    }

    /** Counts a wrong PIN and locks the PIN once maxAttempts wrong PINs in a row were entered. */
    public void recordPinFailure(int maxAttempts, Instant now) {
        this.pinFailedAttempts++;
        if (pinFailedAttempts >= maxAttempts) {
            this.pinLockedAt = now;
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
