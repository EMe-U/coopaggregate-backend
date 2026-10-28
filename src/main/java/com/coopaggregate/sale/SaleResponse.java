package com.coopaggregate.sale;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.lot.LotStatus;

/**
 * lotId, lotCode and lotStatus are only set for sales made before sales by grade; a sale by grade takes its kg from
 * several lots, which are in batches. Each batch line is the kg taken from one delivery and what its member was
 * credited for them. totalDeductionRwf, totalNetRwf and the money of each line are null for a sale made before sales
 * by grade: its members are paid through the lot share.
 */
public record SaleResponse(
        Long id,
        String saleCode,
        String orderCode,
        Long gradeId,
        String gradeName,
        Long lotId,
        String lotCode,
        LotStatus lotStatus,
        Long buyerId,
        String buyerName,
        BigDecimal quantityKg,
        Long pricePerKg,
        Long totalAmountRwf,
        boolean belowFloor,
        LocalDate soldAt,
        Instant createdAt,
        Long totalDeductionRwf,
        Long totalNetRwf,
        List<BatchLine> batches) {

    public record BatchLine(
            Long deliveryId,
            String receiptCode,
            Long lotId,
            String lotCode,
            Long memberId,
            String memberNumber,
            String memberName,
            BigDecimal kg,
            Long grossRwf,
            Long deductionRwf,
            Long netRwf) {

        static BatchLine from(SaleAllocation allocation) {
            return new BatchLine(allocation.getDelivery().getId(), allocation.getDelivery().getReceiptCode(),
                    allocation.getDelivery().getLot().getId(), allocation.getDelivery().getLot().getLotCode(),
                    allocation.getDelivery().getMember().getId(), allocation.getDelivery().getMember().getMemberCode(),
                    allocation.getDelivery().getMember().getFullName(), allocation.getQuantityKg(),
                    allocation.getGrossRwf(), allocation.getDeductionRwf(), allocation.netRwf());
        }
    }

    /** Reads the sale's grade, lot and buyer and the batches' members, so call it inside a transaction. */
    public static SaleResponse from(Sale sale, List<SaleAllocation> allocations) {
        List<BatchLine> batches = allocations.stream().map(BatchLine::from).toList();
        boolean credited = !batches.isEmpty() && batches.stream().allMatch(batch -> batch.grossRwf() != null);
        return new SaleResponse(
                sale.getId(),
                sale.getSaleCode(),
                sale.getSaleOrder() == null ? null : sale.getSaleOrder().getOrderCode(),
                sale.getGrade().getId(),
                sale.getGrade().getName(),
                sale.getLot() == null ? null : sale.getLot().getId(),
                sale.getLot() == null ? null : sale.getLot().getLotCode(),
                sale.getLot() == null ? null : sale.getLot().getStatus(),
                sale.getBuyer().getId(),
                sale.getBuyer().getName(),
                sale.getQuantityKg(),
                sale.getPricePerKg(),
                sale.getTotalAmount(),
                sale.isBelowFloorPrice(),
                sale.getSaleDate(),
                sale.getCreatedAt(),
                credited ? batches.stream().mapToLong(BatchLine::deductionRwf).sum() : null,
                credited ? batches.stream().mapToLong(BatchLine::netRwf).sum() : null,
                batches);
    }
}
