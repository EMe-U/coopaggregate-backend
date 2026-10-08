package com.coopaggregate.dispute;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import com.coopaggregate.ledger.LedgerEntry;
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

@Entity
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private LedgerEntry ledgerEntry;

    @ManyToOne(fetch = FetchType.LAZY)
    private Member member;

    @Enumerated(EnumType.STRING)
    private DisputeChannel raisedVia;

    private String description;

    @Enumerated(EnumType.STRING)
    private DisputeStatus status = DisputeStatus.OPEN;

    private String resolution;

    private Instant resolvedAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public LedgerEntry getLedgerEntry() {
        return ledgerEntry;
    }

    public void setLedgerEntry(LedgerEntry ledgerEntry) {
        this.ledgerEntry = ledgerEntry;
    }

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
    }

    public DisputeChannel getRaisedVia() {
        return raisedVia;
    }

    public void setRaisedVia(DisputeChannel raisedVia) {
        this.raisedVia = raisedVia;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public DisputeStatus getStatus() {
        return status;
    }

    public void setStatus(DisputeStatus status) {
        this.status = status;
    }

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
