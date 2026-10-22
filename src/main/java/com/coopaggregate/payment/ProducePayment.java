package com.coopaggregate.payment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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
public class ProducePayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID clientUuid;

    private String paymentCode;

    @ManyToOne(fetch = FetchType.LAZY)
    private Member member;

    private LocalDate paymentDate;

    private Long amount;

    @Enumerated(EnumType.STRING)
    private PaymentMethod method;

    private String referenceNo;

    private Instant reversedAt;

    private String reversalReason;

    @ManyToOne(fetch = FetchType.LAZY)
    private LedgerEntry reversalLedgerEntry;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public UUID getClientUuid() {
        return clientUuid;
    }

    public void setClientUuid(UUID clientUuid) {
        this.clientUuid = clientUuid;
    }

    public String getPaymentCode() {
        return paymentCode;
    }

    public void setPaymentCode(String paymentCode) {
        this.paymentCode = paymentCode;
    }

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public void setPaymentDate(LocalDate paymentDate) {
        this.paymentDate = paymentDate;
    }

    public Long getAmount() {
        return amount;
    }

    public void setAmount(Long amount) {
        this.amount = amount;
    }

    public PaymentMethod getMethod() {
        return method;
    }

    public void setMethod(PaymentMethod method) {
        this.method = method;
    }

    public String getReferenceNo() {
        return referenceNo;
    }

    public void setReferenceNo(String referenceNo) {
        this.referenceNo = referenceNo;
    }

    public boolean isReversed() {
        return reversedAt != null;
    }

    public Instant getReversedAt() {
        return reversedAt;
    }

    public String getReversalReason() {
        return reversalReason;
    }

    public LedgerEntry getReversalLedgerEntry() {
        return reversalLedgerEntry;
    }

    /** Marks this record as cancelled by the given REVERSAL ledger entry. */
    public void markReversed(LedgerEntry reversal) {
        this.reversedAt = reversal.getEntryDate();
        this.reversalReason = reversal.getReason();
        this.reversalLedgerEntry = reversal;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
