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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

    @Enumerated(EnumType.STRING)
    private ConfirmationStatus confirmationStatus = ConfirmationStatus.PENDING;

    @Enumerated(EnumType.STRING)
    private ConfirmationMethod confirmationMethod;

    private Instant confirmedAt;

    @Enumerated(EnumType.STRING)
    private DisputeReason disputeReason;

    private String disputeNote;

    private Instant disputedAt;

    private String resolutionNote;

    // Only a BCrypt hash of the one-time code is stored; the code itself is only in the SMS.
    private String confirmationCodeHash;

    private Instant confirmationCodeExpiresAt;

    private int confirmationCodeFailedAttempts;

    private int confirmationCodeResends;

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

    public ConfirmationStatus getConfirmationStatus() {
        return confirmationStatus;
    }

    public ConfirmationMethod getConfirmationMethod() {
        return confirmationMethod;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public DisputeReason getDisputeReason() {
        return disputeReason;
    }

    public String getDisputeNote() {
        return disputeNote;
    }

    public Instant getDisputedAt() {
        return disputedAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void confirm(ConfirmationMethod method, Instant now) {
        this.confirmationStatus = ConfirmationStatus.CONFIRMED;
        this.confirmationMethod = method;
        this.confirmedAt = now;
        clearCode();
    }

    public String getConfirmationCodeHash() {
        return confirmationCodeHash;
    }

    public Instant getConfirmationCodeExpiresAt() {
        return confirmationCodeExpiresAt;
    }

    public int getConfirmationCodeFailedAttempts() {
        return confirmationCodeFailedAttempts;
    }

    public int getConfirmationCodeResends() {
        return confirmationCodeResends;
    }

    /** A new code replaces any earlier one, and its wrong tries start again from 0. */
    public void issueCode(String codeHash, Instant expiresAt) {
        this.confirmationCodeHash = codeHash;
        this.confirmationCodeExpiresAt = expiresAt;
        this.confirmationCodeFailedAttempts = 0;
    }

    public void clearCode() {
        this.confirmationCodeHash = null;
        this.confirmationCodeExpiresAt = null;
    }

    public void recordWrongCode() {
        this.confirmationCodeFailedAttempts++;
    }

    public void dispute(DisputeReason reason, String note, Instant now) {
        this.confirmationStatus = ConfirmationStatus.DISPUTED;
        this.disputeReason = reason;
        this.disputeNote = note;
        this.disputedAt = now;
    }

    /** The manager checked the delivery and found it correct. */
    public void resolveDispute(String note, Instant now) {
        this.resolutionNote = note;
        confirm(ConfirmationMethod.MANAGER_REVIEW, now);
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
