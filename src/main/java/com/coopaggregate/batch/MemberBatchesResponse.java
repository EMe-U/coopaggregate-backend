package com.coopaggregate.batch;

import com.coopaggregate.common.PageResponse;

/** A member's batches, newest first, with the kg totals and the money credited by batch over all of them. */
public record MemberBatchesResponse(
        Long memberId,
        String memberNumber,
        String memberName,
        BatchTotals totals,
        BatchEarnings earnings,
        PageResponse<BatchResponse> batches) {
}
