package com.coopaggregate.share;

import com.coopaggregate.error.ErrorCoded;

/**
 * A lot that cannot be shared: no kg delivered (LOT_EMPTY), a fixed-size lot whose members are paid by sale
 * (LOT_PAID_BY_SALES), or no sale money (LOT_NO_SALE_MONEY). Answered with 409.
 */
public class LotNotShareableException extends IllegalStateException implements ErrorCoded {

    private final String errorCode;

    private LotNotShareableException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    static LotNotShareableException empty(String lotCode) {
        return new LotNotShareableException("LOT_EMPTY",
                "Lot " + lotCode + " has no kg delivered, so there is nothing to share.");
    }

    static LotNotShareableException paidBySales(String lotCode) {
        return new LotNotShareableException("LOT_PAID_BY_SALES", "Lot " + lotCode + " is a fixed-size lot: its "
                + "members are paid by each sale of their batches, so it is never shared.");
    }

    static LotNotShareableException noSaleMoney(String lotCode) {
        return new LotNotShareableException("LOT_NO_SALE_MONEY",
                "Lot " + lotCode + " has no sale money, so there is nothing to share.");
    }

    @Override
    public String errorCode() {
        return errorCode;
    }
}
