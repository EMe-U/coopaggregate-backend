package com.coopaggregate.loss;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import org.hibernate.annotations.CreationTimestamp;

import com.coopaggregate.lot.Lot;

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
public class Loss {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Lot lot;

    private LocalDate lossDate;

    private BigDecimal quantityKg;

    @Enumerated(EnumType.STRING)
    private LossReason reason;

    private String notes;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public Lot getLot() {
        return lot;
    }

    public void setLot(Lot lot) {
        this.lot = lot;
    }

    public LocalDate getLossDate() {
        return lossDate;
    }

    public void setLossDate(LocalDate lossDate) {
        this.lossDate = lossDate;
    }

    public BigDecimal getQuantityKg() {
        return quantityKg;
    }

    public void setQuantityKg(BigDecimal quantityKg) {
        this.quantityKg = quantityKg;
    }

    public LossReason getReason() {
        return reason;
    }

    public void setReason(LossReason reason) {
        this.reason = reason;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
