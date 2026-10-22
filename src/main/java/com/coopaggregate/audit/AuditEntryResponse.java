package com.coopaggregate.audit;

import java.time.Instant;

public record AuditEntryResponse(
        Long id,
        AuditAction action,
        String details,
        Long memberId,
        String memberName,
        String managerName,
        Instant createdAt) {

    public static AuditEntryResponse from(AuditLog entry) {
        return new AuditEntryResponse(
                entry.getId(),
                entry.getAction(),
                entry.getDetails(),
                entry.getMember() == null ? null : entry.getMember().getId(),
                entry.getMember() == null ? null : entry.getMember().getFullName(),
                entry.getManager() == null ? null : entry.getManager().getName(),
                entry.getCreatedAt());
    }
}
