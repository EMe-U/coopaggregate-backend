package com.coopaggregate.payment;

import java.util.List;

public record OwedMembersResponse(List<OwedMember> members, long totalOwedRwf) {

    public record OwedMember(Long memberId, String memberName, String memberNumber, String phone, long owedRwf) {
    }
}
