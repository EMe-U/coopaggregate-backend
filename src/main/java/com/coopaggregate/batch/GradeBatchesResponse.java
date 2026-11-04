package com.coopaggregate.batch;

import java.math.BigDecimal;

import com.coopaggregate.common.PageResponse;

/** A grade's batches that still have kg, oldest first: the order sales and losses use them in. */
public record GradeBatchesResponse(
        Long gradeId,
        String gradeName,
        BigDecimal remainingKg,
        PageResponse<BatchResponse> batches) {
}
