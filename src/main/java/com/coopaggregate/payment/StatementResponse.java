package com.coopaggregate.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.coopaggregate.lot.LotStatus;

public record StatementResponse(
        long grossShareRwf,
        long deductionsRwf,
        long paidRwf,
        long owedRwf,
        List<LotShareLine> lotShares,
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

    public record PaymentLine(String paymentCode, LocalDate paidAt, long amountRwf, PaymentMethod method,
                              String reference) {
    }
}
