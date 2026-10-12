package com.coopaggregate.sale;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import com.coopaggregate.lot.LotStatus;

public record SaleResponse(
        Long id,
        String saleCode,
        String orderCode,
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
        Instant createdAt) {

    public static SaleResponse from(Sale sale) {
        return new SaleResponse(
                sale.getId(),
                sale.getSaleCode(),
                sale.getSaleOrder() == null ? null : sale.getSaleOrder().getOrderCode(),
                sale.getLot().getId(),
                sale.getLot().getLotCode(),
                sale.getLot().getStatus(),
                sale.getBuyer().getId(),
                sale.getBuyer().getName(),
                sale.getQuantityKg(),
                sale.getPricePerKg(),
                sale.getTotalAmount(),
                sale.isBelowFloorPrice(),
                sale.getSaleDate(),
                sale.getCreatedAt());
    }
}
