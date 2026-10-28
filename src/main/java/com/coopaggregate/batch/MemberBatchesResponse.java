package com.coopaggregate.batch;

import com.coopaggregate.common.PageResponse;

/** A member's batches, newest first, with the totals over all of them (not only this page). */
public record MemberBatchesResponse(
        Long memberId,
        String memberNumber,
        String memberName,
        BatchTotals totals,
        PageResponse<BatchResponse> batches) {
}
