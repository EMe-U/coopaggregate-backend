package com.coopaggregate.payment;

import java.util.List;

public record BalancesResponse(long totalOwedRwf, long paidThisWeekRwf, List<MemberBalance> members) {

    public record MemberBalance(
            Long memberId,
            String memberName,
            String memberNumber,
            String phone,
            long shareRwf,
            long deductionRwf,
            long paidRwf,
            long balanceRwf,
            BalanceStatus status) {
    }
}
