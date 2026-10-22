package com.coopaggregate.identity;

import java.time.Instant;

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
}
