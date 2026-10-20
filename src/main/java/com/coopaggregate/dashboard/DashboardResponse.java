package com.coopaggregate.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

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
        long pendingConfirmationsOver48h) {

    public record GradeKg(String gradeName, BigDecimal kg) {
    }

    public record OpenLot(Long id, String code, String gradeName) {
    }

    public record WeekDeliveries(LocalDate weekStart, List<GradeKg> grades) {
    }
}
