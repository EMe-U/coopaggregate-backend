package com.coopaggregate.share;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

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
public class LotShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Lot lot;

    @ManyToOne(fetch = FetchType.LAZY)
    private Member member;

    private BigDecimal memberKg;

    private BigDecimal lotKg;

    private Long shareAmount;

    private Long deductionAmount = 0L;

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

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
    }

    public BigDecimal getMemberKg() {
        return memberKg;
    }

    public void setMemberKg(BigDecimal memberKg) {
        this.memberKg = memberKg;
    }

    public BigDecimal getLotKg() {
        return lotKg;
    }

    public void setLotKg(BigDecimal lotKg) {
        this.lotKg = lotKg;
    }

    public Long getShareAmount() {
        return shareAmount;
    }

    public void setShareAmount(Long shareAmount) {
        this.shareAmount = shareAmount;
    }

    public Long getDeductionAmount() {
        return deductionAmount;
    }

    public void setDeductionAmount(Long deductionAmount) {
        this.deductionAmount = deductionAmount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
