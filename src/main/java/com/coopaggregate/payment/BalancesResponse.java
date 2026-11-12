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
            BalanceStatus status,
            // OWED while the balance is more than 0, SETTLED once it is disbursed. Unlike status, a settled member
            // who earns again from a new sale is simply OWED again, not part paid.
            SettlementStatus settlement) {
    }
}
