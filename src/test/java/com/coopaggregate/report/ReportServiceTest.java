package com.coopaggregate.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.buyer.Buyer;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.delivery.ConfirmationMethod;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.delivery.GradeDeliveredKg;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.loss.LossReason;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.StockResponse;
import com.coopaggregate.payment.BalancesResponse;
import com.coopaggregate.payment.PaymentMethod;
import com.coopaggregate.payment.PaymentService;
import com.coopaggregate.payment.ProducePaymentRepository;
import com.coopaggregate.sale.Sale;
import com.coopaggregate.sale.SaleOrder;
import com.coopaggregate.sale.SaleRepository;

class ReportServiceTest {

    // Wednesday 21 October 2026; its week is Monday 19 to Sunday 25 October.
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 21);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 19);
    private static final LocalDate SUNDAY = LocalDate.of(2026, 10, 25);
    // Kigali is UTC+2: Monday 19 October 00:00 there is Sunday 18 October 22:00 UTC.
    private static final Instant FROM = Instant.parse("2026-10-18T22:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-25T22:00:00Z");

    private ReportRepository reportRepository;
    private DeliveryRepository deliveryRepository;
    private SaleRepository saleRepository;
    private ProducePaymentRepository paymentRepository;
    private LotService lotService;
    private PaymentService paymentService;
    private ReportService service;

    @BeforeEach
    void setUp() {
        reportRepository = mock(ReportRepository.class);
        deliveryRepository = mock(DeliveryRepository.class);
        saleRepository = mock(SaleRepository.class);
        paymentRepository = mock(ProducePaymentRepository.class);
        GradeRepository gradeRepository = mock(GradeRepository.class);
        lotService = mock(LotService.class);
        paymentService = mock(PaymentService.class);
        when(gradeRepository.findAll(Sort.by("code"))).thenReturn(List.of(grade("A", "Big"), grade("B", "Normal"),
                grade("C", "Small")));
        when(saleRepository.totalKgBetween(any(), any())).thenReturn(BigDecimal.ZERO);
        when(lotService.stock()).thenReturn(stock(BigDecimal.ZERO, List.of()));
        when(paymentService.balances(null, null)).thenReturn(new BalancesResponse(0, 0, List.of()));
        service = new ReportService(reportRepository, deliveryRepository, saleRepository, paymentRepository,
                gradeRepository, lotService, paymentService);
    }

    // --- The week ---

    @Test
    void weekWithNoActivityIsAllZeros() {
        WeeklyReport report = service.weekly(MONDAY, TODAY);

        assertEquals(new WeeklyReport.Period(MONDAY, SUNDAY), report.period());
        assertEquals(BigDecimal.ZERO, report.deliveries().totalKg());
        assertEquals(0, report.deliveries().deliveries());
        assertEquals(List.of(
                new WeeklyReport.GradeLine("Big", BigDecimal.ZERO, 0),
                new WeeklyReport.GradeLine("Normal", BigDecimal.ZERO, 0),
                new WeeklyReport.GradeLine("Small", BigDecimal.ZERO, 0)), report.deliveries().byGrade());
        assertEquals(List.of(), report.deliveries().byMember());
        assertEquals(BigDecimal.ZERO, report.stock().lostKg());
        assertEquals(5, report.stock().lossesByReason().size());
        assertTrue(report.stock().lossesByReason().stream().allMatch(line -> line.kg().signum() == 0));
        assertEquals(List.of(), report.sales().sales());
        assertEquals(0, report.sales().totalAmountRwf());
        assertEquals(BigDecimal.ZERO, report.sales().averagePricePerKg());
        assertEquals(0, report.money().sharedRwf());
        assertEquals(0, report.money().paidRwf());
        assertEquals(List.of(new WeeklyReport.MethodLine(PaymentMethod.CASH, 0),
                new WeeklyReport.MethodLine(PaymentMethod.MOMO, 0),
                new WeeklyReport.MethodLine(PaymentMethod.BANK, 0)), report.money().paidByMethod());
        assertEquals(new WeeklyReport.Confirmations(0, List.of(), 0, 0, 0, 0), report.confirmations());
    }

    @Test
    void withoutWeekStartTheCurrentWeekIsShown() {
        assertEquals(MONDAY, service.weekly(null, TODAY).period().weekStart());
        assertEquals(MONDAY, service.weekly(null, LocalDate.of(2026, 10, 25)).period().weekStart());
        assertEquals(LocalDate.of(2026, 10, 26), service.weekly(null, LocalDate.of(2026, 10, 26)).period().weekStart());
    }

    @Test
    void weekStartThatIsNotAMondayIsRefused() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.weekly(LocalDate.of(2026, 10, 21), TODAY));

        assertEquals("weekStart must be a Monday, for example 2026-10-19.", e.getMessage());
    }

    @Test
    void futureWeekIsRefusedButPastAndCurrentWeeksAreNot() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.weekly(LocalDate.of(2026, 10, 26), TODAY));

        assertEquals("weekStart 2026-10-26 is in the future. The latest week starts on 2026-10-19.", e.getMessage());
        assertEquals(MONDAY, service.weekly(MONDAY, TODAY).period().weekStart());
        assertEquals(LocalDate.of(2025, 1, 6), service.weekly(LocalDate.of(2025, 1, 6), TODAY).period().weekStart());
    }

    @Test
    void weekRunsFromMondayMidnightToTheNextMondayMidnightInKigali() {
        service.weekly(MONDAY, TODAY);

        verify(deliveryRepository).findKgByGradeBetween(FROM, TO);
        verify(reportRepository).countDeliveriesByGradeBetween(FROM, TO);
        verify(reportRepository).findMemberGradeKgBetween(FROM, TO);
        verify(reportRepository).sumSharedBetween(FROM, TO);
        verify(reportRepository).findConfirmedByMethodBetween(FROM, TO);
        verify(reportRepository).countDisputesSettledBetween(FROM, TO);
        // Records with a date use the dates Monday to Sunday.
        verify(saleRepository).totalAmountBetween(MONDAY, SUNDAY);
        verify(paymentRepository).paidBetween(MONDAY, SUNDAY);
        verify(reportRepository).findLossKgByReasonBetween(MONDAY, SUNDAY);
    }

    @Test
    void sundayNightBelongsToThatWeekAndMondayMorningToTheNext() {
        Instant sundayNight = LocalDateTime.of(2026, 10, 25, 23, 59, 59).atZone(KigaliTime.ZONE).toInstant();
        Instant mondayMorning = LocalDateTime.of(2026, 10, 26, 0, 0).atZone(KigaliTime.ZONE).toInstant();

        // A delivery at 23:59:59 on Sunday is inside [FROM, TO); one at midnight on Monday is the next week's.
        assertTrue(!sundayNight.isBefore(FROM) && sundayNight.isBefore(TO));
        assertFalse(mondayMorning.isBefore(TO));
        assertEquals(TO, mondayMorning);
        service.weekly(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 26));
        verify(deliveryRepository).findKgByGradeBetween(TO, Instant.parse("2026-11-01T22:00:00Z"));
    }

    // --- Sections ---

    @Test
    void deliveriesPerGradeAndPerMemberSortedByTotalKg() {
        when(deliveryRepository.findKgByGradeBetween(FROM, TO)).thenReturn(List.of(
                new GradeDeliveredKg("Big", new BigDecimal("300.50")),
                new GradeDeliveredKg("Small", new BigDecimal("80.00"))));
        when(reportRepository.countDeliveriesByGradeBetween(FROM, TO)).thenReturn(List.of(
                new GradeCount("Big", 3L), new GradeCount("Small", 1L)));
        when(reportRepository.findMemberGradeKgBetween(FROM, TO)).thenReturn(List.of(
                new MemberGradeKg(5L, "M-0005", "Uwimana Claudine", "Big", new BigDecimal("100.00")),
                new MemberGradeKg(6L, "M-0006", "Habimana Eric", "Big", new BigDecimal("200.50")),
                new MemberGradeKg(5L, "M-0005", "Uwimana Claudine", "Small", new BigDecimal("80.00"))));

        WeeklyReport.Deliveries deliveries = service.weekly(MONDAY, TODAY).deliveries();

        assertEquals(new BigDecimal("380.50"), deliveries.totalKg());
        assertEquals(4, deliveries.deliveries());
        assertEquals(List.of(
                new WeeklyReport.GradeLine("Big", new BigDecimal("300.50"), 3),
                new WeeklyReport.GradeLine("Normal", BigDecimal.ZERO, 0),
                new WeeklyReport.GradeLine("Small", new BigDecimal("80.00"), 1)), deliveries.byGrade());
        assertEquals(List.of(
                new WeeklyReport.MemberLine(6L, "M-0006", "Habimana Eric", List.of(
                        new WeeklyReport.GradeKg("Big", new BigDecimal("200.50")),
                        new WeeklyReport.GradeKg("Normal", BigDecimal.ZERO),
                        new WeeklyReport.GradeKg("Small", BigDecimal.ZERO)), new BigDecimal("200.50")),
                new WeeklyReport.MemberLine(5L, "M-0005", "Uwimana Claudine", List.of(
                        new WeeklyReport.GradeKg("Big", new BigDecimal("100.00")),
                        new WeeklyReport.GradeKg("Normal", BigDecimal.ZERO),
                        new WeeklyReport.GradeKg("Small", new BigDecimal("80.00"))), new BigDecimal("180.00"))),
                deliveries.byMember());
    }

    @Test
    void stockIsTheStockPageNowAndLossesAreByReason() {
        when(lotService.stock()).thenReturn(stock(new BigDecimal("550.00"), List.of(
                new StockResponse.GradeStock("Big", new BigDecimal("250.00")),
                new StockResponse.GradeStock("Normal", new BigDecimal("300.00")))));
        when(reportRepository.findLossKgByReasonBetween(MONDAY, SUNDAY)).thenReturn(List.of(
                new ReasonKg(LossReason.ROT, new BigDecimal("12.50")),
                new ReasonKg(LossReason.THEFT, new BigDecimal("3.00"))));

        WeeklyReport.Stock stock = service.weekly(MONDAY, TODAY).stock();

        assertEquals(new BigDecimal("550.00"), stock.remainingKgNow());
        assertEquals(List.of(new WeeklyReport.GradeKg("Big", new BigDecimal("250.00")),
                new WeeklyReport.GradeKg("Normal", new BigDecimal("300.00"))), stock.remainingByGradeNow());
        assertEquals(new BigDecimal("15.50"), stock.lostKg());
        assertEquals(List.of(
                new WeeklyReport.ReasonLine(LossReason.ROT, new BigDecimal("12.50")),
                new WeeklyReport.ReasonLine(LossReason.DRYING, BigDecimal.ZERO),
                new WeeklyReport.ReasonLine(LossReason.DAMAGE, BigDecimal.ZERO),
                new WeeklyReport.ReasonLine(LossReason.THEFT, new BigDecimal("3.00")),
                new WeeklyReport.ReasonLine(LossReason.OTHER, BigDecimal.ZERO)), stock.lossesByReason());
    }

    @Test
    void salesAreOneLinePerOrderOrSaleWithTheSalesPageTotals() {
        Buyer kalisa = buyer("Kalisa Traders");
        SaleOrder order = new SaleOrder();
        ReflectionTestUtils.setField(order, "id", 1L);
        order.setOrderCode("ORD-7K2Q");
        when(reportRepository.findSalesBetween(MONDAY, SUNDAY)).thenReturn(List.of(
                sale(10L, "SAL-AAAA", "A-2026-02", kalisa, order, "300.00", 105_000L, LocalDate.of(2026, 10, 20)),
                sale(11L, "SAL-BBBB", "A-2026-01", kalisa, order, "200.00", 70_000L, LocalDate.of(2026, 10, 20)),
                sale(12L, "SAL-CCCC", "B-2026-01", buyer("Mukamana Hotel"), null, "100.50", 30_150L,
                        LocalDate.of(2026, 10, 22))));
        when(saleRepository.totalKgBetween(MONDAY, SUNDAY)).thenReturn(new BigDecimal("600.50"));
        when(saleRepository.totalAmountBetween(MONDAY, SUNDAY)).thenReturn(205_150L);

        WeeklyReport.Sales sales = service.weekly(MONDAY, TODAY).sales();

        assertEquals(List.of(
                new WeeklyReport.SaleLine("ORD-7K2Q", LocalDate.of(2026, 10, 20), "Kalisa Traders",
                        List.of("A-2026-01", "A-2026-02"), new BigDecimal("500.00"), 175_000L),
                new WeeklyReport.SaleLine("SAL-CCCC", LocalDate.of(2026, 10, 22), "Mukamana Hotel",
                        List.of("B-2026-01"), new BigDecimal("100.50"), 30_150L)), sales.sales());
        assertEquals(new BigDecimal("600.50"), sales.totalKg());
        assertEquals(205_150L, sales.totalAmountRwf());
        assertEquals(new BigDecimal("341.63"), sales.averagePricePerKg());
        assertEquals(sales.totalAmountRwf(), sales.sales().stream().mapToLong(WeeklyReport.SaleLine::amountRwf).sum());
    }

    @Test
    void moneyHasSharesPaymentsByMethodAndWhatIsOwedNow() {
        when(reportRepository.sumSharedBetween(FROM, TO)).thenReturn(2_952_000L);
        when(reportRepository.sumShareDeductionsBetween(FROM, TO)).thenReturn(50_000L);
        when(reportRepository.findPaidByMethodBetween(MONDAY, SUNDAY)).thenReturn(List.of(
                new MethodAmount(PaymentMethod.MOMO, 400_000L), new MethodAmount(PaymentMethod.CASH, 100_000L)));
        when(paymentRepository.paidBetween(MONDAY, SUNDAY)).thenReturn(500_000L);
        when(paymentService.balances(null, null)).thenReturn(new BalancesResponse(1_321_600L, 0, List.of()));

        WeeklyReport.Money money = service.weekly(MONDAY, TODAY).money();

        assertEquals(2_952_000L, money.sharedRwf());
        assertEquals(50_000L, money.shareDeductionsRwf());
        assertEquals(2_902_000L, money.sharedNetRwf());
        assertEquals(500_000L, money.paidRwf());
        assertEquals(List.of(new WeeklyReport.MethodLine(PaymentMethod.CASH, 100_000L),
                new WeeklyReport.MethodLine(PaymentMethod.MOMO, 400_000L),
                new WeeklyReport.MethodLine(PaymentMethod.BANK, 0)), money.paidByMethod());
        assertEquals(1_321_600L, money.owedNowRwf());
    }

    @Test
    void confirmationsInTheWeekAndCountsNow() {
        when(reportRepository.findConfirmedByMethodBetween(FROM, TO)).thenReturn(List.of(
                new MethodCount(ConfirmationMethod.MANAGER_REVIEW, 1L), new MethodCount(ConfirmationMethod.OTP, 7L)));
        when(reportRepository.countPendingDeliveredBetween(FROM, TO)).thenReturn(2L);
        when(deliveryRepository.countByConfirmationStatusAndReversedAtIsNull(ConfirmationStatus.PENDING)).thenReturn(5L);
        when(deliveryRepository.countByConfirmationStatusAndReversedAtIsNull(ConfirmationStatus.DISPUTED)).thenReturn(1L);
        when(reportRepository.countDisputesSettledBetween(FROM, TO)).thenReturn(3L);

        WeeklyReport.Confirmations confirmations = service.weekly(MONDAY, TODAY).confirmations();

        assertEquals(new WeeklyReport.Confirmations(8, List.of(
                new WeeklyReport.ConfirmedLine(ConfirmationMethod.OTP, 7),
                new WeeklyReport.ConfirmedLine(ConfirmationMethod.MANAGER_REVIEW, 1)), 2, 5, 1, 3), confirmations);
    }

    // --- Reversed records ---

    @Test
    void everyDeliveryAndPaymentQueryLeavesOutReversedRecords() {
        Map<String, String> queries = Map.of(
                "countDeliveriesByGradeBetween", "d.reversedAt IS NULL",
                "findMemberGradeKgBetween", "d.reversedAt IS NULL",
                "findPaidByMethodBetween", "p.reversedAt IS NULL",
                "findConfirmedByMethodBetween", "d.reversedAt IS NULL",
                "countPendingDeliveredBetween", "d.reversedAt IS NULL");
        for (var method : ReportRepository.class.getDeclaredMethods()) {
            String filter = queries.get(method.getName());
            if (filter != null) {
                assertTrue(method.getAnnotation(Query.class).value().contains(filter),
                        method.getName() + " must leave out reversed records");
            }
        }
        assertEquals(5, queries.keySet().stream().filter(name -> java.util.Arrays.stream(
                ReportRepository.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals(name))).count());
    }

    @Test
    void aReversedDisputeCountsAsSettledInTheWeekItWasReversed() {
        String query = java.util.Arrays.stream(ReportRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("countDisputesSettledBetween")).findFirst().orElseThrow()
                .getAnnotation(Query.class).value();

        assertTrue(query.contains("d.reversedAt >= :from AND d.reversedAt < :to"));
        assertTrue(query.contains("d.confirmedAt >= :from AND d.confirmedAt < :to"));
    }

    private static Grade grade(String code, String name) {
        Grade grade = new Grade();
        grade.setCode(code);
        grade.setName(name);
        return grade;
    }

    private static Buyer buyer(String name) {
        Buyer buyer = new Buyer();
        buyer.setName(name);
        return buyer;
    }

    private static Sale sale(Long id, String code, String lotCode, Buyer buyer, SaleOrder order, String kg,
                             long amount, LocalDate date) {
        Lot lot = new Lot();
        lot.setLotCode(lotCode);
        Sale sale = new Sale();
        ReflectionTestUtils.setField(sale, "id", id);
        sale.setSaleCode(code);
        sale.setLot(lot);
        sale.setBuyer(buyer);
        sale.setSaleOrder(order);
        sale.setQuantityKg(new BigDecimal(kg));
        sale.setTotalAmount(amount);
        sale.setSaleDate(date);
        return sale;
    }

    static StockResponse stock(BigDecimal remaining, List<StockResponse.GradeStock> byGrade) {
        return new StockResponse(remaining, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 0, 0, 0, byGrade);
    }
}
