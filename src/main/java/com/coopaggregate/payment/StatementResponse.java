package com.coopaggregate.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.coopaggregate.lot.LotStatus;

/**
 * A member's earnings and payments. grossShareRwf and deductionsRwf add up the lot shares (sales before sales by
 * grade) and the sale credits by batch; owedRwf is what is left after payments.
 */
public record StatementResponse(
        long grossShareRwf,
        long deductionsRwf,
        long paidRwf,
        long owedRwf,
        List<LotShareLine> lotShares,
        List<SaleCreditLine> saleCredits,
        List<PaymentLine> payments) {

    /** shareRwf is null while the lot is not shared yet. */
    public record LotShareLine(
            Long lotId,
            String lotCode,
            LotStatus status,
            BigDecimal memberKg,
            BigDecimal lotKg,
            BigDecimal sharePercent,
            Long shareRwf) {
    }

    /** What one sale paid the member for one of their batches. */
    public record SaleCreditLine(
            String saleCode,
            LocalDate soldAt,
            String receiptCode,
            String lotCode,
            BigDecimal kg,
            long pricePerKg,
            long grossRwf,
            long deductionRwf,
            long netRwf) {
    }

    public record PaymentLine(String paymentCode, LocalDate paidAt, long amountRwf, PaymentMethod method,
                              String reference) {
    }
}
