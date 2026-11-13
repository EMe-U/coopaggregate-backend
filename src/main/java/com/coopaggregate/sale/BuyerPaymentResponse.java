package com.coopaggregate.sale;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.member.Member;
import com.coopaggregate.payment.PaymentMethod;

/**
 * Money in: a buyer pays a whole order once, in full, so each order is one buyer payment. method and reference are
 * null for orders saved before they were recorded (shown as "not recorded"). farmers is what each member was credited
 * from the order, summed over all their batches in all its grades. A sale from before sales by grade credited its
 * members through the lot share instead: those members have kg but no money, and the order's totalDeductionRwf and
 * totalNetRwf are null.
 */
public record BuyerPaymentResponse(
        Long orderId,
        String orderCode,
        Long buyerId,
        String buyerName,
        LocalDate paidAt,
        Instant createdAt,
        PaymentMethod method,
        String reference,
        BigDecimal totalKg,
        long totalAmountRwf,
        Long totalDeductionRwf,
        Long totalNetRwf,
        List<SaleLine> sales,
        List<FarmerShare> farmers) {

    public record SaleLine(Long saleId, String saleCode, Long gradeId, String gradeName, BigDecimal kg,
                           Long pricePerKg, Long amountRwf) {
    }

    public record FarmerShare(Long memberId, String memberNumber, String memberName, BigDecimal kg, Long grossRwf,
                              Long deductionRwf, Long netRwf) {
    }

    /** Reads the order's buyer, the sales' grades and the batches' members, so call it inside a transaction. */
    public static BuyerPaymentResponse from(SaleOrder order, List<Sale> sales, List<SaleAllocation> allocations) {
        List<SaleLine> saleLines = sales.stream()
                .map(sale -> new SaleLine(sale.getId(), sale.getSaleCode(), sale.getGrade().getId(),
                        sale.getGrade().getName(), sale.getQuantityKg(), sale.getPricePerKg(), sale.getTotalAmount()))
                .toList();
        List<FarmerShare> farmers = farmers(allocations);
        boolean credited = !farmers.isEmpty() && farmers.stream().allMatch(farmer -> farmer.grossRwf() != null);
        return new BuyerPaymentResponse(
                order.getId(),
                order.getOrderCode(),
                order.getBuyer().getId(),
                order.getBuyer().getName(),
                order.getSaleDate(),
                order.getCreatedAt(),
                order.getPaymentMethod(),
                order.getPaymentReference(),
                saleLines.stream().map(SaleLine::kg).reduce(BigDecimal.ZERO, BigDecimal::add),
                saleLines.stream().mapToLong(SaleLine::amountRwf).sum(),
                credited ? farmers.stream().mapToLong(FarmerShare::deductionRwf).sum() : null,
                credited ? farmers.stream().mapToLong(FarmerShare::netRwf).sum() : null,
                saleLines,
                farmers);
    }

    // One line per member, by name: their kg and money added up over every batch of theirs in the order.
    private static List<FarmerShare> farmers(List<SaleAllocation> allocations) {
        Map<Long, Total> byMember = new LinkedHashMap<>();
        for (SaleAllocation allocation : allocations) {
            Member member = allocation.getDelivery().getMember();
            byMember.computeIfAbsent(member.getId(), id -> new Total(member)).add(allocation);
        }
        List<FarmerShare> farmers = new ArrayList<>();
        for (Total total : byMember.values()) {
            farmers.add(total.share());
        }
        farmers.sort(Comparator.comparing(FarmerShare::memberName).thenComparing(FarmerShare::memberId));
        return farmers;
    }

    private static final class Total {
        private final Member member;
        private BigDecimal kg = BigDecimal.ZERO;
        private long gross;
        private long deduction;
        private boolean withoutMoney;

        Total(Member member) {
            this.member = member;
        }

        void add(SaleAllocation allocation) {
            kg = kg.add(allocation.getQuantityKg());
            if (allocation.getGrossRwf() == null) {
                withoutMoney = true;
            } else {
                gross += allocation.getGrossRwf();
                deduction += allocation.getDeductionRwf();
            }
        }

        FarmerShare share() {
            return new FarmerShare(member.getId(), member.getMemberCode(), member.getFullName(), kg,
                    withoutMoney ? null : gross, withoutMoney ? null : deduction,
                    withoutMoney ? null : gross - deduction);
        }
    }
}
