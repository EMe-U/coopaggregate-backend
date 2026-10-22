package com.coopaggregate.audit;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import com.coopaggregate.manager.Manager;
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
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    private AuditAction action;

    @ManyToOne(fetch = FetchType.LAZY)
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    private Manager manager;

    private String details;

    @CreationTimestamp
    @Column(updatable = false)
    private Instant createdAt;

    public static AuditLog of(AuditAction action, Member member, Manager manager, String details) {
        AuditLog log = new AuditLog();
        log.action = action;
        log.member = member;
        log.manager = manager;
        log.details = details;
        return log;
    }

    public Long getId() {
        return id;
    }

    public AuditAction getAction() {
        return action;
    }

    public Member getMember() {
        return member;
    }

    public Manager getManager() {
        return manager;
    }

    public String getDetails() {
        return details;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
