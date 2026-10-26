package com.coopaggregate.report;

import java.time.LocalDate;

/** A week the frontend can offer: Monday to Sunday. */
public record ReportWeek(LocalDate weekStart, LocalDate weekEnd) {
}
