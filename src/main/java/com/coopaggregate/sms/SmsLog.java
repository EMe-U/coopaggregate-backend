package com.coopaggregate.sms;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import com.coopaggregate.delivery.Delivery;
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
public class SmsLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    private Delivery delivery;

    private String phone;

    // The text as sent, except that a one-time code is replaced by SmsMessages.CODE_MASK.
    private String message;

    // 0 for a delivery's first message, 1 to 3 for codes the manager sent again.
    private int sendNumber;

    @Enumerated(EnumType.STRING)
    private SmsStatus status = SmsStatus.QUEUED;

    private String provider;

    private int attempts;

    private String lastError;

    private Instant sentAt;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public Long getId() {
        return id;
    }

    public Member getMember() {
        return member;
    }

    public void setMember(Member member) {
        this.member = member;
    }

    public Delivery getDelivery() {
        return delivery;
    }

    public void setDelivery(Delivery delivery) {
        this.delivery = delivery;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getSendNumber() {
        return sendNumber;
    }

    public void setSendNumber(int sendNumber) {
        this.sendNumber = sendNumber;
    }

    public SmsStatus getStatus() {
        return status;
    }

    public String getProvider() {
        return provider;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void markSent(String provider, Instant now) {
        this.attempts++;
        this.provider = provider;
        this.status = SmsStatus.SENT;
        this.sentAt = now;
        this.lastError = null;
    }

    public void markFailed(String provider, String error) {
        this.attempts++;
        this.provider = provider;
        this.status = SmsStatus.FAILED;
        this.lastError = error;
    }

    public void cancel(String reason) {
        this.status = SmsStatus.CANCELLED;
        this.lastError = reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
