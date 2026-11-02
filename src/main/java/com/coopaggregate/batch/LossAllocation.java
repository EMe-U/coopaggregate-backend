package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.loss.Loss;
import com.coopaggregate.lot.LotPortion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** The kg a loss took from one batch (delivery). */
@Entity
public class LossAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Loss loss;

    @ManyToOne(fetch = FetchType.LAZY)
    private Delivery delivery;

    // The portion of the delivery the kg came from. Null for losses recorded before fixed-size lots: their delivery
    // has one portion, in its own lot.
    @ManyToOne(fetch = FetchType.LAZY)
    private LotPortion lotPortion;

    private BigDecimal quantityKg;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    protected LossAllocation() {
    }

    public LossAllocation(Loss loss, Delivery delivery, BigDecimal quantityKg) {
        this.loss = loss;
        this.delivery = delivery;
        this.quantityKg = quantityKg;
    }

    public Long getId() {
        return id;
    }

    public Loss getLoss() {
        return loss;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public LotPortion getLotPortion() {
        return lotPortion;
    }

    public void setLotPortion(LotPortion lotPortion) {
        this.lotPortion = lotPortion;
    }

    public BigDecimal getQuantityKg() {
        return quantityKg;
    }

    // When a reversed batch hands its lost kg back, they may land on a batch this loss already uses.
    void addKg(BigDecimal kg) {
        this.quantityKg = quantityKg.add(kg);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
