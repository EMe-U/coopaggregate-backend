package com.coopaggregate.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.delivery.GradeDeliveredKg;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.loss.LossReason;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.StockResponse;
import com.coopaggregate.payment.PaymentMethod;
import com.coopaggregate.payment.PaymentService;
import com.coopaggregate.payment.ProducePaymentRepository;
import com.coopaggregate.sale.Sale;
import com.coopaggregate.sale.SaleRepository;

/**
 * The weekly report. Numbers that another page already shows come from the same query or service (delivered kg per
 * grade from the dashboard's query, stock from the stock page, sale totals from the sales summary, paid and owed from
 * the balances, open disputes from the dashboard), so they always match.
 */
@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final DeliveryRepository deliveryRepository;
    private final SaleRepository saleRepository;
    private final ProducePaymentRepository paymentRepository;
    private final GradeRepository gradeRepository;
    private final LotService lotService;
    private final PaymentService paymentService;

    public ReportService(ReportRepository reportRepository, DeliveryRepository deliveryRepository,
                         SaleRepository saleRepository, ProducePaymentRepository paymentRepository,
                         GradeRepository gradeRepository, LotService lotService, PaymentService paymentService) {
        this.reportRepository = reportRepository;
        this.deliveryRepository = deliveryRepository;
        this.saleRepository = saleRepository;
        this.paymentRepository = paymentRepository;
        this.gradeRepository = gradeRepository;
        this.lotService = lotService;
        this.paymentService = paymentService;
    }

    /** weekStart null means the current week. */
    @Transactional(readOnly = true)
    public WeeklyReport weekly(LocalDate weekStart) {
        return weekly(weekStart, KigaliTime.today());
    }

    // Takes today's date as a parameter so tests can use fixed weeks.
    WeeklyReport weekly(LocalDate weekStart, LocalDate today) {
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate monday = weekStart != null ? weekStart : thisMonday;
        if (monday.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new BadRequestException("weekStart must be a Monday, for example "
                    + monday.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) + ".");
        }
        if (monday.isAfter(thisMonday)) {
            throw new BadRequestException("weekStart " + monday + " is in the future. The latest week starts on "
                    + thisMonday + ".");
        }
        LocalDate sunday = monday.plusDays(6);
        // Deliveries, confirmations and shares are moments in time: the week runs from Monday 00:00 to the next
        // Monday 00:00 in Kigali. Sales, payments and losses have a date: Monday to Sunday.
        Instant from = startOfDay(monday);
        Instant to = startOfDay(monday.plusWeeks(1));
        List<String> gradeNames = gradeRepository.findAll(Sort.by("code")).stream().map(Grade::getName).toList();

        return new WeeklyReport(
                new WeeklyReport.Period(monday, sunday),
                deliveries(from, to, gradeNames),
                stock(monday, sunday),
                sales(monday, sunday),
                money(from, to, monday, sunday),
                confirmations(from, to));
    }

    private WeeklyReport.Deliveries deliveries(Instant from, Instant to, List<String> gradeNames) {
        // The dashboard's query, so the kg per grade are the same as on the home screen.
        Map<String, BigDecimal> kgByGrade = deliveryRepository.findKgByGradeBetween(from, to).stream()
                .collect(Collectors.toMap(GradeDeliveredKg::gradeName, GradeDeliveredKg::kg));
        Map<String, Long> countByGrade = reportRepository.countDeliveriesByGradeBetween(from, to).stream()
                .collect(Collectors.toMap(GradeCount::gradeName, GradeCount::deliveries));
        List<WeeklyReport.GradeLine> byGrade = gradeNames.stream()
                .map(name -> new WeeklyReport.GradeLine(name, kgByGrade.getOrDefault(name, BigDecimal.ZERO),
                        countByGrade.getOrDefault(name, 0L)))
                .toList();

        Map<Long, List<MemberGradeKg>> rowsByMember = reportRepository.findMemberGradeKgBetween(from, to).stream()
                .collect(Collectors.groupingBy(MemberGradeKg::memberId, LinkedHashMap::new, Collectors.toList()));
        List<WeeklyReport.MemberLine> byMember = rowsByMember.values().stream()
                .map(rows -> memberLine(rows, gradeNames))
                .sorted(Comparator.comparing(WeeklyReport.MemberLine::totalKg).reversed()
                        .thenComparing(WeeklyReport.MemberLine::memberName))
                .toList();

        return new WeeklyReport.Deliveries(
                byGrade.stream().map(WeeklyReport.GradeLine::kg).reduce(BigDecimal.ZERO, BigDecimal::add),
                byGrade.stream().mapToLong(WeeklyReport.GradeLine::deliveries).sum(),
                byGrade, byMember);
    }

    private static WeeklyReport.MemberLine memberLine(List<MemberGradeKg> rows, List<String> gradeNames) {
        Map<String, BigDecimal> kgByGrade = rows.stream()
                .collect(Collectors.toMap(MemberGradeKg::gradeName, MemberGradeKg::kg));
        MemberGradeKg first = rows.get(0);
        return new WeeklyReport.MemberLine(first.memberId(), first.memberNumber(), first.memberName(),
                gradeNames.stream()
                        .map(name -> new WeeklyReport.GradeKg(name, kgByGrade.getOrDefault(name, BigDecimal.ZERO)))
                        .toList(),
                kgByGrade.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    private WeeklyReport.Stock stock(LocalDate monday, LocalDate sunday) {
        StockResponse stock = lotService.stock();
        Map<LossReason, BigDecimal> lostByReason = reportRepository.findLossKgByReasonBetween(monday, sunday)
                .stream().collect(Collectors.toMap(ReasonKg::reason, ReasonKg::kg));
        List<WeeklyReport.ReasonLine> losses = Arrays.stream(LossReason.values())
                .map(reason -> new WeeklyReport.ReasonLine(reason, lostByReason.getOrDefault(reason, BigDecimal.ZERO)))
                .toList();
        return new WeeklyReport.Stock(
                stock.totalRemainingKg(),
                stock.byGrade().stream()
                        .map(grade -> new WeeklyReport.GradeKg(grade.gradeName(), grade.remainingKg()))
                        .toList(),
                lostByReason.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add),
                losses);
    }

    private WeeklyReport.Sales sales(LocalDate monday, LocalDate sunday) {
        // One line per sale order (all its lots), or per sale that was recorded on its own.
        Map<String, List<Sale>> salesByLine = reportRepository.findSalesBetween(monday, sunday).stream()
                .collect(Collectors.groupingBy(
                        sale -> sale.getSaleOrder() != null ? "order:" + sale.getSaleOrder().getId()
                                : "sale:" + sale.getId(),
                        LinkedHashMap::new, Collectors.toList()));
        List<WeeklyReport.SaleLine> lines = new ArrayList<>();
        for (List<Sale> sales : salesByLine.values()) {
            Sale first = sales.get(0);
            lines.add(new WeeklyReport.SaleLine(
                    first.getSaleOrder() != null ? first.getSaleOrder().getOrderCode() : first.getSaleCode(),
                    first.getSaleDate(),
                    first.getBuyer().getName(),
                    sales.stream().map(sale -> sale.getLot().getLotCode()).distinct().sorted().toList(),
                    sales.stream().map(Sale::getQuantityKg).reduce(BigDecimal.ZERO, BigDecimal::add),
                    sales.stream().mapToLong(Sale::getTotalAmount).sum()));
        }

        // The sales summary's queries and average, so the totals match the sales page.
        long amount = saleRepository.totalAmountBetween(monday, sunday);
        BigDecimal kg = saleRepository.totalKgBetween(monday, sunday);
        BigDecimal averagePricePerKg = kg.signum() == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(amount).divide(kg, 2, RoundingMode.HALF_UP);
        return new WeeklyReport.Sales(lines, kg, amount, averagePricePerKg);
    }

    private WeeklyReport.Money money(Instant from, Instant to, LocalDate monday, LocalDate sunday) {
        long shared = reportRepository.sumSharedBetween(from, to);
        long deductions = reportRepository.sumShareDeductionsBetween(from, to);
        Map<PaymentMethod, Long> paidByMethod = reportRepository.findPaidByMethodBetween(monday, sunday).stream()
                .collect(Collectors.toMap(MethodAmount::method, MethodAmount::amountRwf));
        return new WeeklyReport.Money(
                shared,
                deductions,
                shared - deductions,
                // The same query as "paid this week" on the balances page.
                paymentRepository.paidBetween(monday, sunday),
                Arrays.stream(PaymentMethod.values())
                        .map(method -> new WeeklyReport.MethodLine(method, paidByMethod.getOrDefault(method, 0L)))
                        .toList(),
                paymentService.balances(null, null).totalOwedRwf());
    }

    private WeeklyReport.Confirmations confirmations(Instant from, Instant to) {
        List<WeeklyReport.ConfirmedLine> byMethod = reportRepository.findConfirmedByMethodBetween(from, to).stream()
                .map(row -> new WeeklyReport.ConfirmedLine(row.method(), row.deliveries()))
                .sorted(Comparator.comparing(WeeklyReport.ConfirmedLine::method))
                .toList();
        return new WeeklyReport.Confirmations(
                byMethod.stream().mapToLong(WeeklyReport.ConfirmedLine::deliveries).sum(),
                byMethod,
                reportRepository.countPendingDeliveredBetween(from, to),
                deliveryRepository.countByConfirmationStatusAndReversedAtIsNull(ConfirmationStatus.PENDING),
                // The same count as the dashboard and the disputes page.
                deliveryRepository.countByConfirmationStatusAndReversedAtIsNull(ConfirmationStatus.DISPUTED),
                reportRepository.countDisputesSettledBetween(from, to));
    }

    private static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(KigaliTime.ZONE).toInstant();
    }
}
