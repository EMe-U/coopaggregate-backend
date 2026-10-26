package com.coopaggregate.dispute;

import java.math.BigDecimal;

import com.coopaggregate.common.PageResponse;

/**
 * The disputes page. The totals cover all disputes, not only this page or search: openDisputes and openKg are the
 * deliveries still disputed (the same count as the dashboard), settledDisputes the ones closed so far.
 */
public record DisputesResponse(
        long openDisputes,
        BigDecimal openKg,
        long settledDisputes,
        PageResponse<DisputeItem> disputes) {
}
