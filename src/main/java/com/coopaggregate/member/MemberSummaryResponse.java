package com.coopaggregate.member;

public record MemberSummaryResponse(long total, long active, long inactive, long joinedThisMonth) {
}
