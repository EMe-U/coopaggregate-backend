package com.coopaggregate.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.delivery.GradeDeliveredKg;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotQuantities;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.lot.StockResponse;
import com.coopaggregate.member.MemberService;
import com.coopaggregate.member.MemberSummaryResponse;
import com.coopaggregate.payment.BalanceStatus;
import com.coopaggregate.payment.BalancesResponse;
import com.coopaggregate.payment.PaymentService;

class DashboardServiceTest {

    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 10, 14);

    private MemberService memberService;
    private LotService lotService;
    private PaymentService paymentService;
    private LotRepository lotRepository;
    private DeliveryRepository deliveryRepository;
    private DashboardService service;

    private Grade big;
    private Grade normal;
    private Grade small;

    @BeforeEach
    void setUp() {
        memberService = mock(MemberService.class);
        lotService = mock(LotService.class);
        paymentService = mock(PaymentService.class);
        lotRepository = mock(LotRepository.class);
        deliveryRepository = mock(DeliveryRepository.class);
        GradeRepository gradeRepository = mock(GradeRepository.class);
        service = new DashboardService(memberService, lotService, paymentService, lotRepository,
                deliveryRepository, gradeRepository);

        big = grade("A", "Big");
        normal = grade("B", "Normal");
        small = grade("C", "Small");
        when(gradeRepository.findAll(any(Sort.class))).thenReturn(List.of(big, normal, small));

        when(memberService.summary()).thenReturn(new MemberSummaryResponse(0, 0, 0, 0));
        when(lotService.stock()).thenReturn(new StockResponse(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, 0, List.of()));
        when(paymentService.balances(null, null)).thenReturn(new BalancesResponse(0, 0, List.of()));
    }

    @Test
    void valuesMatchMemberStockAndBalancePages() {
        when(memberService.summary()).thenReturn(new MemberSummaryResponse(12, 10, 2, 3));
        when(lotService.stock()).thenReturn(new StockResponse(new BigDecimal("550.00"), new BigDecimal("550.00"),
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("4000.00"),
                new BigDecimal("3300.00"), new BigDecimal("150.00"), BigDecimal.ZERO, new BigDecimal("200.00"), 3,
                1, 2,
                List.of(new StockResponse.GradeStock("Big", new BigDecimal("250.00"), new BigDecimal("250.00"), BigDecimal.ZERO, BigDecimal.ZERO),
                        new StockResponse.GradeStock("Normal", new BigDecimal("300.00"), new BigDecimal("300.00"), BigDecimal.ZERO, BigDecimal.ZERO))));
        when(paymentService.balances(null, null)).thenReturn(new BalancesResponse(1_321_600L, 200_000L, List.of(
                balance(2L, 870_600L, BalanceStatus.UNPAID),
                balance(1L, 451_000L, BalanceStatus.PART_PAID),
                balance(3L, 0L, BalanceStatus.PAID))));

        DashboardResponse dashboard = service.dashboard(WEDNESDAY);

        assertEquals(12, dashboard.totalMembers());
        assertEquals(10, dashboard.activeMembers());
        assertEquals(3, dashboard.membersJoinedThisMonth());
        assertEquals(new BigDecimal("550.00"), dashboard.stockKg());
        assertEquals(List.of(new DashboardResponse.GradeKg("Big", new BigDecimal("250.00")),
                new DashboardResponse.GradeKg("Normal", new BigDecimal("300.00"))), dashboard.stockByGrade());
        assertEquals(new BigDecimal("200.00"), dashboard.deliveriesTodayKg());
        assertEquals(3, dashboard.deliveriesTodayCount());
        assertEquals(1_321_600L, dashboard.owedToMembersRwf());
        assertEquals(2, dashboard.membersAwaitingPayment());
    }

    @Test
    void openLotsAreListedByGrade() {
        when(lotRepository.findByStatusIn(List.of(LotStatus.OPEN))).thenReturn(List.of(
                lot(7L, "B-2026-03", normal, LotStatus.OPEN), lot(6L, "A-2026-05", big, LotStatus.OPEN)));

        DashboardResponse dashboard = service.dashboard(WEDNESDAY);

        assertEquals(List.of(new DashboardResponse.OpenLot(6L, "A-2026-05", "Big"),
                new DashboardResponse.OpenLot(7L, "B-2026-03", "Normal")), dashboard.openLots());
    }

    @Test
    void readyToShareCountsSoldLots() {
        when(lotRepository.countByStatus(LotStatus.SOLD)).thenReturn(2L);

        assertEquals(2, service.dashboard(WEDNESDAY).lotsReadyToShare());
    }

    @Test
    void gradesWithSellableKgAreCountedWhateverTheirLotsStatus() {
        // Big has sellable kg (some of them in open lots: no lot has to be closed). Normal has kg left but none of
        // them is confirmed yet. Small has nothing left and is not listed.
        when(lotService.stock()).thenReturn(new StockResponse(new BigDecimal("700.00"), new BigDecimal("400.00"),
                new BigDecimal("250.00"), new BigDecimal("50.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 2, 1, List.of(
                new StockResponse.GradeStock("Big", new BigDecimal("400.00"), new BigDecimal("400.00"),
                        BigDecimal.ZERO, BigDecimal.ZERO),
                new StockResponse.GradeStock("Normal", new BigDecimal("300.00"), BigDecimal.ZERO,
                        new BigDecimal("250.00"), new BigDecimal("50.00")))));

        DashboardResponse dashboard = service.dashboard(WEDNESDAY);

        assertEquals(1, dashboard.gradesWithSellableKg());
        assertEquals(1, dashboard.lotsReadyToSell(), "kept for older screens: the same number");
    }

    @Test
    void stockIsSplitIntoSellableWaitingAndDisputedKgInTotalAndPerGrade() {
        when(lotService.stock()).thenReturn(new StockResponse(new BigDecimal("575.50"), new BigDecimal("500.00"),
                new BigDecimal("65.50"), new BigDecimal("10.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 1, 2, List.of(
                new StockResponse.GradeStock("Big", new BigDecimal("250.00"), new BigDecimal("200.00"),
                        new BigDecimal("40.00"), new BigDecimal("10.00")),
                new StockResponse.GradeStock("Normal", new BigDecimal("325.50"), new BigDecimal("300.00"),
                        new BigDecimal("25.50"), BigDecimal.ZERO))));

        DashboardResponse dashboard = service.dashboard(WEDNESDAY);

        // The existing fields keep their meaning: all the kg left.
        assertEquals(new BigDecimal("575.50"), dashboard.stockKg());
        assertEquals(List.of(new DashboardResponse.GradeKg("Big", new BigDecimal("250.00")),
                new DashboardResponse.GradeKg("Normal", new BigDecimal("325.50"))), dashboard.stockByGrade());
        // The new fields split them.
        assertEquals(new BigDecimal("500.00"), dashboard.sellableKg());
        assertEquals(new BigDecimal("65.50"), dashboard.waitingKg());
        assertEquals(new BigDecimal("10.00"), dashboard.disputedKg());
        assertEquals(List.of(
                new DashboardResponse.GradeStockKg("Big", new BigDecimal("250.00"), new BigDecimal("200.00"),
                        new BigDecimal("40.00"), new BigDecimal("10.00")),
                new DashboardResponse.GradeStockKg("Normal", new BigDecimal("325.50"), new BigDecimal("300.00"),
                        new BigDecimal("25.50"), BigDecimal.ZERO)), dashboard.stockByGradeDetail());
        assertEquals(2, dashboard.gradesWithSellableKg());
    }

    @Test
    void weeklyDeliveriesCoverFourMondayWeeksOldestFirstWithEveryGrade() {
        when(deliveryRepository.findKgByGradeBetween(start(2026, 10, 5), start(2026, 10, 12))).thenReturn(List.of(
                new GradeDeliveredKg("Big", new BigDecimal("500.00")),
                new GradeDeliveredKg("Small", new BigDecimal("75.50"))));
        when(deliveryRepository.findKgByGradeBetween(start(2026, 10, 12), start(2026, 10, 19))).thenReturn(List.of(
                new GradeDeliveredKg("Normal", new BigDecimal("120.00"))));

        List<DashboardResponse.WeekDeliveries> weeks = service.dashboard(WEDNESDAY).weeklyDeliveries();

        assertEquals(List.of(LocalDate.of(2026, 9, 21), LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 5),
                LocalDate.of(2026, 10, 12)), weeks.stream().map(DashboardResponse.WeekDeliveries::weekStart).toList());
        assertEquals(grades("0", "0", "0"), weeks.get(0).grades());
        assertEquals(grades("0", "0", "0"), weeks.get(1).grades());
        assertEquals(grades("500.00", "0", "75.50"), weeks.get(2).grades());
        assertEquals(grades("0", "120.00", "0"), weeks.get(3).grades());
    }

    @Test
    void weekWithSeveralGradesListsEachGradeWithItsKg() {
        when(deliveryRepository.findKgByGradeBetween(start(2026, 10, 12), start(2026, 10, 19))).thenReturn(List.of(
                new GradeDeliveredKg("Small", new BigDecimal("40.00")),
                new GradeDeliveredKg("Big", new BigDecimal("1200.50")),
                new GradeDeliveredKg("Normal", new BigDecimal("310.25"))));
        when(deliveryRepository.findKgByGradeBetween(start(2026, 10, 5), start(2026, 10, 12))).thenReturn(List.of(
                new GradeDeliveredKg("Normal", new BigDecimal("90.00"))));

        List<DashboardResponse.WeekDeliveries> weeks = service.dashboard(WEDNESDAY).weeklyDeliveries();

        assertEquals(grades("1200.50", "310.25", "40.00"), weeks.get(3).grades());
        assertEquals(grades("0", "90.00", "0"), weeks.get(2).grades());
        weeks.forEach(week -> assertEquals(List.of("Big", "Normal", "Small"),
                week.grades().stream().map(DashboardResponse.GradeKg::gradeName).toList()));
    }

    @Test
    void mondayAndSundayBelongToTheSameWeek() {
        LocalDate monday = LocalDate.of(2026, 10, 12);
        LocalDate sunday = LocalDate.of(2026, 10, 18);

        assertEquals(monday, lastWeekStart(service.dashboard(monday)));
        assertEquals(monday, lastWeekStart(service.dashboard(sunday)));
    }

    @Test
    void confirmationProblemsAreCounted() {
        when(deliveryRepository.countByConfirmationStatusAndReversedAtIsNull(ConfirmationStatus.DISPUTED))
                .thenReturn(2L);
        when(deliveryRepository.countByConfirmationStatusAndReversedAtIsNullAndCreatedAtBefore(
                eq(ConfirmationStatus.PENDING), any())).thenReturn(5L);

        DashboardResponse dashboard = service.dashboard(WEDNESDAY);

        assertEquals(2, dashboard.disputedDeliveries());
        assertEquals(5, dashboard.pendingConfirmationsOver48h());
    }

    @Test
    void pendingCountUsesDeliveriesSavedMoreThan48HoursAgo() {
        Instant before = Instant.now();

        service.dashboard(WEDNESDAY);

        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(deliveryRepository).countByConfirmationStatusAndReversedAtIsNullAndCreatedAtBefore(
                eq(ConfirmationStatus.PENDING), cutoff.capture());
        Duration age = Duration.between(cutoff.getValue(), before);
        assertTrue(age.compareTo(Duration.ofHours(47)) > 0 && age.compareTo(Duration.ofHours(48)) <= 0, age.toString());
    }

    @Test
    void emptyCooperativeShowsZeros() {
        DashboardResponse dashboard = service.dashboard(WEDNESDAY);

        assertEquals(0, dashboard.totalMembers());
        assertEquals(BigDecimal.ZERO, dashboard.stockKg());
        assertEquals(List.of(), dashboard.openLots());
        assertEquals(0, dashboard.owedToMembersRwf());
        assertEquals(0, dashboard.membersAwaitingPayment());
        assertEquals(0, dashboard.lotsReadyToShare());
        assertEquals(0, dashboard.lotsReadyToSell());
        assertEquals(0, dashboard.gradesWithSellableKg());
        assertEquals(BigDecimal.ZERO, dashboard.sellableKg());
        assertEquals(BigDecimal.ZERO, dashboard.waitingKg());
        assertEquals(BigDecimal.ZERO, dashboard.disputedKg());
        assertEquals(List.of(), dashboard.stockByGradeDetail());
        assertEquals(4, dashboard.weeklyDeliveries().size());
        dashboard.weeklyDeliveries().forEach(week -> assertEquals(grades("0", "0", "0"), week.grades()));
    }

    private static LocalDate lastWeekStart(DashboardResponse dashboard) {
        return dashboard.weeklyDeliveries().get(3).weekStart();
    }

    private static List<DashboardResponse.GradeKg> grades(String bigKg, String normalKg, String smallKg) {
        return List.of(new DashboardResponse.GradeKg("Big", new BigDecimal(bigKg)),
                new DashboardResponse.GradeKg("Normal", new BigDecimal(normalKg)),
                new DashboardResponse.GradeKg("Small", new BigDecimal(smallKg)));
    }

    private static Instant start(int year, int month, int day) {
        return LocalDate.of(year, month, day).atStartOfDay(KigaliTime.ZONE).toInstant();
    }

    private static BalancesResponse.MemberBalance balance(Long memberId, long balanceRwf, BalanceStatus status) {
        return new BalancesResponse.MemberBalance(memberId, "Member " + memberId, "M-000" + memberId, "+250788000000",
                0L, 0L, 0L, balanceRwf, status);
    }

    private static Grade grade(String code, String name) {
        Grade grade = new Grade();
        grade.setCode(code);
        grade.setName(name);
        return grade;
    }

    private static Lot lot(Long id, String code, Grade grade, LotStatus status) {
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", id);
        lot.setLotCode(code);
        lot.setGrade(grade);
        lot.setStatus(status);
        return lot;
    }
}
