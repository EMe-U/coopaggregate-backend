package com.coopaggregate.buyer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;

import com.coopaggregate.grade.Grade;

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
public class BuyerRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String referenceCode;

    @ManyToOne(fetch = FetchType.LAZY)
    private Buyer buyer;

    @ManyToOne(fetch = FetchType.LAZY)
    private Grade grade;

    private BigDecimal quantityKg;

    private LocalDate preferredDate;

    private String message;

    @Enumerated(EnumType.STRING)
    private BuyerRequestStatus status = BuyerRequestStatus.PENDING;

    private String managerNote;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public String getReferenceCode() {
        return referenceCode;
    }

    public void setReferenceCode(String referenceCode) {
        this.referenceCode = referenceCode;
    }

    public Buyer getBuyer() {
        return buyer;
    }

    public void setBuyer(Buyer buyer) {
        this.buyer = buyer;
    }

    public Grade getGrade() {
        return grade;
    }

    public void setGrade(Grade grade) {
        this.grade = grade;
    }

    public BigDecimal getQuantityKg() {
        return quantityKg;
    }

    public void setQuantityKg(BigDecimal quantityKg) {
        this.quantityKg = quantityKg;
    }

    public LocalDate getPreferredDate() {
        return preferredDate;
    }

    public void setPreferredDate(LocalDate preferredDate) {
        this.preferredDate = preferredDate;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public BuyerRequestStatus getStatus() {
        return status;
    }

    public void setStatus(BuyerRequestStatus status) {
        this.status = status;
    }

    public String getManagerNote() {
        return managerNote;
    }

    public void setManagerNote(String managerNote) {
        this.managerNote = managerNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
