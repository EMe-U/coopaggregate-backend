package com.coopaggregate.sale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.coopaggregate.buyer.BuyerResponse;

public record SaleOrderResponse(
        String orderCode,
        BuyerResponse buyer,
        LocalDate soldAt,
        BigDecimal totalKg,
        long totalAmountRwf,
        List<SaleResponse> sales) {

    public static SaleOrderResponse from(SaleOrder order, List<SaleResponse> sales) {
        BigDecimal totalKg = sales.stream().map(SaleResponse::quantityKg).reduce(BigDecimal.ZERO, BigDecimal::add);
        long totalAmount = sales.stream().mapToLong(SaleResponse::totalAmountRwf).sum();
        return new SaleOrderResponse(order.getOrderCode(), BuyerResponse.from(order.getBuyer()), order.getSaleDate(),
                totalKg, totalAmount, sales);
    }
}
