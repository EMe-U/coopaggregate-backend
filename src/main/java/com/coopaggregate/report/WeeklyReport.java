package com.coopaggregate.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.coopaggregate.delivery.ConfirmationMethod;
import com.coopaggregate.loss.LossReason;
import com.coopaggregate.payment.PaymentMethod;

/**
 * One week, Monday to Sunday in Kigali time. Amounts are whole RWF. Sections marked "now" show the situation at the
 * moment of the request, not at the end of that week.
 */
public record WeeklyReport(
        Period period,
        Deliveries deliveries,
        Stock stock,
        Sales sales,
        Money money,
        Confirmations confirmations) {

    public record Period(LocalDate weekStart, LocalDate weekEnd) {
    }

    /** By delivery date. byMember is sorted by total kg, highest first. */
    public record Deliveries(BigDecimal totalKg, long deliveries, List<GradeLine> byGrade, List<MemberLine> byMember) {
    }

    public record GradeLine(String gradeName, BigDecimal kg, long deliveries) {
    }

    public record MemberLine(Long memberId, String memberNumber, String memberName, List<GradeKg> kgByGrade,
                             BigDecimal totalKg) {
    }

    public record GradeKg(String gradeName, BigDecimal kg) {
    }

    /** Remaining kg now (as on the stock page); losses by loss date in the week. */
    public record Stock(BigDecimal remainingKgNow, List<GradeKg> remainingByGradeNow, BigDecimal lostKg,
                        List<ReasonLine> lossesByReason) {
    }

    public record ReasonLine(LossReason reason, BigDecimal kg) {
    }

    /** By sale date. A multi-lot order is one line with all its lot codes. */
    public record Sales(List<SaleLine> sales, BigDecimal totalKg, long totalAmountRwf, BigDecimal averagePricePerKg) {
    }

    public record SaleLine(String code, LocalDate saleDate, String buyerName, List<String> lotCodes, BigDecimal kg,
                           long amountRwf) {
    }

    /**
     * sharedRwf is the gross share of lots shared in the week (by the time they were shared) plus the gross sale
     * credits by batch made in the week; shareDeductionsRwf the deductions in both. paidRwf is by payment date;
     * owedNowRwf is what members are owed now.
     */
    public record Money(long sharedRwf, long shareDeductionsRwf, long sharedNetRwf, long paidRwf,
                        List<MethodLine> paidByMethod, long owedNowRwf) {
    }

    public record MethodLine(PaymentMethod method, long amountRwf) {
    }

    /**
     * confirmedThisWeek counts confirmations made in the week (by confirmation time). pendingFromThisWeek is the
     * week's deliveries still pending now; pendingNow and disputedNow are all of them, now.
     */
    public record Confirmations(long confirmedThisWeek, List<ConfirmedLine> confirmedByMethod,
                                long pendingFromThisWeek, long pendingNow, long disputedNow,
                                long disputesSettledThisWeek) {
    }

    public record ConfirmedLine(ConfirmationMethod method, long deliveries) {
    }
}
