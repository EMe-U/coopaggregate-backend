package com.coopaggregate.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.payment.PaymentService;
import com.coopaggregate.payment.ProducePaymentRepository;
import com.coopaggregate.sale.SaleRepository;

class ReportWeeksTest {

    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final ReportService service = new ReportService(reportRepository, mock(DeliveryRepository.class),
            mock(SaleRepository.class), mock(ProducePaymentRepository.class), mock(GradeRepository.class),
            mock(LotService.class), mock(PaymentService.class));

    @Test
    void weeksAreMondayToSundayNewestFirstFromTheLast52() {
        // Wednesday 21 October 2026: the window is the 52 weeks from Monday 27 October 2025 to Monday 19 October 2026.
        LocalDate since = LocalDate.of(2025, 10, 27);
        when(reportRepository.findWeeksWithActivity(since, Instant.parse("2025-10-26T22:00:00Z"),
                LocalDate.of(2026, 10, 19))).thenReturn(List.of(
                        java.sql.Date.valueOf("2026-10-19"), LocalDate.of(2026, 10, 5)));

        List<ReportWeek> weeks = service.weeksWithActivity(LocalDate.of(2026, 10, 21));

        assertEquals(List.of(
                new ReportWeek(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 25)),
                new ReportWeek(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 11))), weeks);
        assertEquals(51, java.time.temporal.ChronoUnit.WEEKS.between(since, LocalDate.of(2026, 10, 19)));
    }

    @Test
    void noActivityGivesNoWeeks() {
        assertEquals(List.of(), service.weeksWithActivity(LocalDate.of(2026, 10, 21)));
    }

    @Test
    void weeksQueryLeavesOutReversedRecordsAndUsesKigaliWeeks() {
        String sql = Arrays.stream(ReportRepository.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("findWeeksWithActivity")).findFirst().orElseThrow()
                .getAnnotation(Query.class).value();

        assertTrue(sql.contains("FROM delivery d WHERE d.reversed_at IS NULL"));
        assertTrue(sql.contains("FROM produce_payment p WHERE p.reversed_at IS NULL"));
        assertTrue(sql.contains("d.delivered_at AT TIME ZONE 'Africa/Kigali'"));
        assertTrue(sql.contains("ls.created_at AT TIME ZONE 'Africa/Kigali'"));
        assertTrue(sql.contains("ORDER BY week_start DESC"));
        assertTrue(sql.contains("LIMIT 52"));
        for (String table : List.of("FROM delivery", "FROM sale", "FROM produce_payment", "FROM loss",
                "FROM lot_share")) {
            assertTrue(sql.contains(table), table);
        }
    }
}
