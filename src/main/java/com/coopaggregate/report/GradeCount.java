package com.coopaggregate.report;

/** How many deliveries of one grade a week had. */
public record GradeCount(String gradeName, Long deliveries) {
}
