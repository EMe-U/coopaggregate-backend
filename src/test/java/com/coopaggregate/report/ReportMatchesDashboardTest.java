package com.coopaggregate.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import com.coopaggregate.dashboard.DashboardResponse;
import com.coopaggregate.dashboard.DashboardService;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.delivery.GradeDeliveredKg;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.StockResponse;
import com.coopaggregate.member.MemberService;
import com.coopaggregate.member.MemberSummaryResponse;
import com.coopaggregate.payment.BalancesResponse;
import com.coopaggregate.payment.PaymentService;
import com.coopaggregate.payment.ProducePaymentRepository;
import com.coopaggregate.sale.SaleRepository;

/** The current week's report and the dashboard read the same data, so their shared numbers must be equal. */
class ReportMatchesDashboardTest {

    @Test
    void currentWeekShowsTheSameNumbersAsTheDashboard() {
        DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
        GradeRepository gradeRepository = mock(GradeRepository.class);
        LotService lotService = mock(LotService.class);
        PaymentService paymentService = mock(PaymentService.class);
        MemberService memberService = mock(MemberService.class);
        SaleRepository saleRepository = mock(SaleRepository.class);

        Grade big = new Grade();
        big.setCode("A");
        big.setName("Big");
        Grade normal = new Grade();
        normal.setCode("B");
        normal.setName("Normal");
        when(gradeRepository.findAll(Sort.by("code"))).thenReturn(List.of(big, normal));
        when(deliveryRepository.findKgByGradeBetween(any(), any())).thenReturn(List.of(
                new GradeDeliveredKg("Big", new BigDecimal("420.50")),
                new GradeDeliveredKg("Normal", new BigDecimal("130.00"))));
        when(lotService.stock()).thenReturn(ReportServiceTest.stock(new BigDecimal("550.00"), List.of(
                new StockResponse.GradeStock("Big", new BigDecimal("250.00")),
                new StockResponse.GradeStock("Normal", new BigDecimal("300.00")))));
        when(lotService.quantitiesOf(any(List.class))).thenReturn(Map.of());
        when(paymentService.balances(null, null)).thenReturn(new BalancesResponse(1_321_600L, 200_000L, List.of()));
        when(deliveryRepository.countByConfirmationStatusAndReversedAtIsNull(ConfirmationStatus.DISPUTED))
                .thenReturn(2L);
        when(memberService.summary()).thenReturn(new MemberSummaryResponse(12, 10, 2, 3));
        when(saleRepository.totalKgBetween(any(), any())).thenReturn(BigDecimal.ZERO);

        DashboardResponse dashboard = new DashboardService(memberService, lotService, paymentService,
                mock(LotRepository.class), deliveryRepository, gradeRepository).dashboard();
        WeeklyReport report = new ReportService(mock(ReportRepository.class), deliveryRepository, saleRepository,
                mock(ProducePaymentRepository.class), gradeRepository, lotService, paymentService).weekly(null);

        DashboardResponse.WeekDeliveries thisWeek = dashboard.weeklyDeliveries().get(dashboard.weeklyDeliveries().size() - 1);
        assertEquals(thisWeek.weekStart(), report.period().weekStart());
        assertEquals(thisWeek.grades().stream().map(g -> g.gradeName() + "=" + g.kg()).toList(),
                report.deliveries().byGrade().stream().map(g -> g.gradeName() + "=" + g.kg()).toList());
        assertEquals(dashboard.stockKg(), report.stock().remainingKgNow());
        assertEquals(dashboard.stockByGrade().stream().map(g -> g.gradeName() + "=" + g.kg()).toList(),
                report.stock().remainingByGradeNow().stream().map(g -> g.gradeName() + "=" + g.kg()).toList());
        assertEquals(dashboard.owedToMembersRwf(), report.money().owedNowRwf());
        assertEquals(dashboard.disputedDeliveries(), report.confirmations().disputedNow());
    }
}
