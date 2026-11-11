package com.coopaggregate.identity;

import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;

import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** One verification attempt: started, completed once with the provider's answer, then used for one member. */
@Entity
public class IdentityVerification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // SHA-256 of the state; the state itself only travels to the provider and back.
    private String stateHash;

    private String nonce;

    // PKCE: sent with the code to the token endpoint. Cleared as soon as the state is used.
    private String codeVerifier;

    @ManyToOne(fetch = FetchType.LAZY)
    private Manager manager;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    private Instant expiresAt;

    private Instant usedAt;

    private Instant verifiedAt;

    @Enumerated(EnumType.STRING)
    private VerificationMethod verificationMethod;

    private String identityToken;

    // The columns salt, name_hash, birth_hash, phone_hash and attempts of the old comparison stay in the table, emptied
    // by V37, but are no longer used.

    // The verified name and phone (+2507XXXXXXXX) from the provider. Kept only until the member is saved or for at most
    // 30 minutes, then cleared. Never logged, never in the change history.
    private String verifiedName;
    private String verifiedPhone;
    // The optional verified details (V38), each null when the member did not allow it or it did not fit. Kept and
    // cleared together with the name and phone.
    private LocalDate verifiedBirthdate;
    private String verifiedGender;
    private String verifiedEmail;
    private String verifiedAddress;
    // The national ID from the claim MOSIP_NATIONAL_ID_CLAIM (V39), null when it was not released. Kept and cleared
    // together with the other details.
    private String verifiedNationalId;

    // The member registered with this verification. Set once, so one verification gives one member.
    @ManyToOne(fetch = FetchType.LAZY)
    private Member member;

    public Long getId() {
        return id;
    }

    public String getStateHash() {
        return stateHash;
    }

    public void setStateHash(String stateHash) {
        this.stateHash = stateHash;
    }

    public String getNonce() {
        return nonce;
    }

    public void setNonce(String nonce) {
        this.nonce = nonce;
    }

    public Manager getManager() {
        return manager;
    }

    public void setManager(Manager manager) {
        this.manager = manager;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getUsedAt() {
        return usedAt;
    }

    public void markUsed(Instant now) {
        this.usedAt = now;
        this.codeVerifier = null;
    }

    public String getCodeVerifier() {
        return codeVerifier;
    }

    public void setCodeVerifier(String codeVerifier) {
        this.codeVerifier = codeVerifier;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public VerificationMethod getVerificationMethod() {
        return verificationMethod;
    }

    public String getIdentityToken() {
        return identityToken;
    }

    public void markVerified(VerifiedIdentity identity, Instant now) {
        this.verifiedAt = now;
        this.verificationMethod = identity.method();
        this.identityToken = identity.subject();
    }

    public boolean isVerified() {
        return verifiedAt != null;
    }

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getVerifiedName() {
        return verifiedName;
    }

    public String getVerifiedPhone() {
        return verifiedPhone;
    }

    public LocalDate getVerifiedBirthdate() {
        return verifiedBirthdate;
    }

    public String getVerifiedGender() {
        return verifiedGender;
    }

    public String getVerifiedEmail() {
        return verifiedEmail;
    }

    public String getVerifiedAddress() {
        return verifiedAddress;
    }

    /** The name and phone only, without optional details. */
    public void setVerifiedDetails(String name, String phone) {
        setVerifiedDetails(new VerifiedDetails(name, phone));
    }

    public void setVerifiedDetails(VerifiedDetails details) {
        this.verifiedName = details.fullName();
        this.verifiedPhone = details.phone();
        this.verifiedBirthdate = details.birthdate();
        this.verifiedGender = details.gender();
        this.verifiedEmail = details.email();
        this.verifiedAddress = details.address();
        this.verifiedNationalId = details.nationalId();
    }

    public VerifiedDetails verifiedDetails() {
        return new VerifiedDetails(verifiedName, verifiedPhone, verifiedBirthdate, verifiedGender, verifiedEmail,
                verifiedAddress, verifiedNationalId);
    }

    public String getVerifiedNationalId() {
        return verifiedNationalId;
    }

    public boolean hasVerifiedDetails() {
        return verifiedName != null && verifiedPhone != null;
    }

    /** Clears every verified detail: the name, phone, birth date, gender, email, address and national ID. */
    public void clearVerifiedDetails() {
        setVerifiedDetails(VerifiedDetails.NONE);
    }

    // Never the verified details, the state or the verifier.
    @Override
    public String toString() {
        return "IdentityVerification[id=" + id + "]";
    }
}
