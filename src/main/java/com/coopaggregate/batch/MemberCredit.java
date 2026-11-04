package com.coopaggregate.batch;

/** What a member was credited by batch over all sales, before payments. */
public record MemberCredit(Long memberId, Long grossRwf, Long deductionRwf) {
}
