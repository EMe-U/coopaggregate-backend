package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.sale.Sale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;

/** The kg a sale took from one batch (delivery). */
@Entity
public class SaleAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Sale sale;

    @ManyToOne(fetch = FetchType.LAZY)
    private Delivery delivery;

    private BigDecimal quantityKg;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    protected SaleAllocation() {
    }

    public SaleAllocation(Sale sale, Delivery delivery, BigDecimal quantityKg) {
        this.sale = sale;
        this.delivery = delivery;
        this.quantityKg = quantityKg;
    }

    public Long getId() {
        return id;
    }

    public Sale getSale() {
        return sale;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public BigDecimal getQuantityKg() {
        return quantityKg;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
