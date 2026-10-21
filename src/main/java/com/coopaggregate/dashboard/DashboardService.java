package com.coopaggregate.dashboard;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
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
import com.coopaggregate.payment.BalancesResponse;
import com.coopaggregate.payment.PaymentService;

/** Numbers for the home screen. They come from the same services as the other pages, so they always match. */
@Service
public class DashboardService {

    private static final int WEEKS = 4;

    private final MemberService memberService;
    private final LotService lotService;
    private final PaymentService paymentService;
    private final LotRepository lotRepository;
    private final DeliveryRepository deliveryRepository;
    private final GradeRepository gradeRepository;

    public DashboardService(MemberService memberService, LotService lotService, PaymentService paymentService,
                            LotRepository lotRepository, DeliveryRepository deliveryRepository,
                            GradeRepository gradeRepository) {
        this.memberService = memberService;
        this.lotService = lotService;
        this.paymentService = paymentService;
        this.lotRepository = lotRepository;
        this.deliveryRepository = deliveryRepository;
        this.gradeRepository = gradeRepository;
    }

    @Transactional(readOnly = true)
    public DashboardResponse dashboard() {
        return dashboard(KigaliTime.today());
    }

    // Takes today's date as a parameter so tests can use fixed weeks.
    DashboardResponse dashboard(LocalDate today) {
        MemberSummaryResponse members = memberService.summary();
        StockResponse stock = lotService.stock();
        BalancesResponse balances = paymentService.balances(null, null);

        List<DashboardResponse.GradeKg> stockByGrade = stock.byGrade().stream()
                .map(grade -> new DashboardResponse.GradeKg(grade.gradeName(), grade.remainingKg()))
                .toList();
        long membersAwaitingPayment = balances.members().stream()
                .filter(member -> member.balanceRwf() > 0)
                .count();

        return new DashboardResponse(
                members.total(),
                members.active(),
                members.joinedThisMonth(),
                stock.totalRemainingKg(),
                stockByGrade,
                stock.addedTodayKg(),
                stock.deliveriesToday(),
                openLots(),
                balances.totalOwedRwf(),
                membersAwaitingPayment,
                lotRepository.countByStatus(LotStatus.SOLD),
                closedLotsWithStock(),
                weeklyDeliveries(today),
                deliveryRepository.countByConfirmationStatusAndReversedAtIsNull(ConfirmationStatus.DISPUTED),
                // Counted from when the server saved the delivery, so a late offline sync is not overdue at once.
                deliveryRepository.countByConfirmationStatusAndReversedAtIsNullAndCreatedAtBefore(
                        ConfirmationStatus.PENDING, Instant.now().minus(Delivery.PENDING_TOO_LONG)));
    }

    private List<DashboardResponse.OpenLot> openLots() {
        return lotRepository.findByStatusIn(List.of(LotStatus.OPEN)).stream()
                .sorted(Comparator.comparing(lot -> lot.getGrade().getCode()))
                .map(lot -> new DashboardResponse.OpenLot(lot.getId(), lot.getLotCode(), lot.getGrade().getName()))
                .toList();
    }

    private long closedLotsWithStock() {
        List<Lot> closedLots = lotRepository.findByStatusIn(List.of(LotStatus.CLOSED));
        Map<Long, LotQuantities> quantities = lotService.quantitiesOf(closedLots);
        return closedLots.stream()
                .filter(lot -> quantities.get(lot.getId()).remainingKg().signum() > 0)
                .count();
    }

    // The current week and the three before it, Monday to Sunday in Kigali time, oldest first.
    private List<DashboardResponse.WeekDeliveries> weeklyDeliveries(LocalDate today) {
        List<String> gradeNames = gradeRepository.findAll(Sort.by("code")).stream().map(Grade::getName).toList();
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        List<DashboardResponse.WeekDeliveries> weeks = new ArrayList<>();
        for (int weeksAgo = WEEKS - 1; weeksAgo >= 0; weeksAgo--) {
            LocalDate weekStart = thisMonday.minusWeeks(weeksAgo);
            Map<String, BigDecimal> kgByGrade = deliveryRepository
                    .findKgByGradeBetween(startOfDay(weekStart), startOfDay(weekStart.plusWeeks(1))).stream()
                    .collect(Collectors.toMap(GradeDeliveredKg::gradeName, GradeDeliveredKg::kg));
            List<DashboardResponse.GradeKg> grades = gradeNames.stream()
                    .map(name -> new DashboardResponse.GradeKg(name, kgByGrade.getOrDefault(name, BigDecimal.ZERO)))
                    .toList();
            weeks.add(new DashboardResponse.WeekDeliveries(weekStart, grades));
        }
        return weeks;
    }

    private static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(KigaliTime.ZONE).toInstant();
    }
}
