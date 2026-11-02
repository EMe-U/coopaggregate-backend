package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.coopaggregate.delivery.Delivery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/**
 * The part of a confirmed delivery that is in one lot. A delivery that did not fit in the lot being filled has a
 * portion in that lot and one in the next. Deliveries recorded before fixed-size lots have exactly one portion, in
 * their own lot.
 */
@Entity
public class LotPortion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Lot lot;

    @ManyToOne(fetch = FetchType.LAZY)
    private Delivery delivery;

    private BigDecimal quantityKg;

    private BigDecimal soldKg = BigDecimal.ZERO;

    private BigDecimal lostKg = BigDecimal.ZERO;

    // quantity_kg - sold_kg - lost_kg, computed by the database. Never written by the app.
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(insertable = false, updatable = false)
    private BigDecimal remainingKg;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    protected LotPortion() {
    }

    public LotPortion(Lot lot, Delivery delivery, BigDecimal quantityKg) {
        this.lot = lot;
        this.delivery = delivery;
        this.quantityKg = quantityKg;
    }

    public Long getId() {
        return id;
    }

    public Lot getLot() {
        return lot;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public BigDecimal getQuantityKg() {
        return quantityKg;
    }

    public BigDecimal getSoldKg() {
        return soldKg;
    }

    public BigDecimal getLostKg() {
        return lostKg;
    }

    /** As stored by the database; see unallocatedKg() for the value while a change is not yet saved. */
    public BigDecimal getRemainingKg() {
        return remainingKg;
    }

    /** The kg not yet sold or lost, from the values in memory. Equal to remainingKg once saved. */
    public BigDecimal unallocatedKg() {
        return quantityKg.subtract(soldKg).subtract(lostKg);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
