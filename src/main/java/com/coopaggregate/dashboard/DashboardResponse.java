package com.coopaggregate.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * stockKg and stockByGrade are the kg left (physical stock). sellableKg, waitingKg and disputedKg split stockKg into
 * the kg that can be sold (confirmed), the kg waiting for the member's confirmation and the disputed kg; stockByGradeDetail
 * does the same per grade. gradesWithSellableKg is the number of grades that have sellable kg: lotsReadyToSell is the
 * same number, kept so older screens still work (it used to count closed lots with stock; a lot no longer has to be
 * closed to be sold).
 */
public record DashboardResponse(
        long totalMembers,
        long activeMembers,
        long membersJoinedThisMonth,
        BigDecimal stockKg,
        List<GradeKg> stockByGrade,
        BigDecimal deliveriesTodayKg,
        long deliveriesTodayCount,
        List<OpenLot> openLots,
        long owedToMembersRwf,
        long membersAwaitingPayment,
        long lotsReadyToShare,
        long lotsReadyToSell,
        List<WeekDeliveries> weeklyDeliveries,
        long disputedDeliveries,
        long pendingConfirmationsOver48h,
        BigDecimal sellableKg,
        BigDecimal waitingKg,
        BigDecimal disputedKg,
        long gradesWithSellableKg,
        List<GradeStockKg> stockByGradeDetail,
        List<LotBeingFilled> lotsBeingFilled) {

    public record GradeKg(String gradeName, BigDecimal kg) {
    }

    public record GradeStockKg(String gradeName, BigDecimal remainingKg, BigDecimal sellableKg, BigDecimal waitingKg,
                               BigDecimal disputedKg) {
    }

    /** openLots lists the lots being filled, as lotsBeingFilled does with their fill level. */
    public record OpenLot(Long id, String code, String gradeName) {
    }

    /** A lot being filled: filledKg of its capacityKg so far. */
    public record LotBeingFilled(Long id, String code, String gradeName, BigDecimal filledKg, BigDecimal capacityKg) {
    }

    public record WeekDeliveries(LocalDate weekStart, List<GradeKg> grades) {
    }
}
