package com.coopaggregate.delivery;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.member.Member;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

@Entity
public class Delivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private UUID clientUuid;

    @ManyToOne(fetch = FetchType.LAZY)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    private Lot lot;

    private Instant deliveredAt;

    private BigDecimal quantityKg;

    private Long deductionAmount = 0L;

    private String receiptCode;

    private Instant reversedAt;

    private String reversalReason;

    @ManyToOne(fetch = FetchType.LAZY)
    private LedgerEntry reversalLedgerEntry;

    // The delivery this one replaces, when it was recorded as a correction.
    @ManyToOne(fetch = FetchType.LAZY)
    private Delivery corrects;

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

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
    }

    public Lot getLot() {
        return lot;
    }

    public void setLot(Lot lot) {
        this.lot = lot;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void setDeliveredAt(Instant deliveredAt) {
        this.deliveredAt = deliveredAt;
    }

    public BigDecimal getQuantityKg() {
        return quantityKg;
    }

    public void setQuantityKg(BigDecimal quantityKg) {
        this.quantityKg = quantityKg;
    }

    public Long getDeductionAmount() {
        return deductionAmount;
    }

    public void setDeductionAmount(Long deductionAmount) {
        this.deductionAmount = deductionAmount;
    }

    public String getReceiptCode() {
        return receiptCode;
    }

    public void setReceiptCode(String receiptCode) {
        this.receiptCode = receiptCode;
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

    public Delivery getCorrects() {
        return corrects;
    }

    public void setCorrects(Delivery corrects) {
        this.corrects = corrects;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
