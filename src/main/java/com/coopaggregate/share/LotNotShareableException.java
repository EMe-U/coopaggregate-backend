package com.coopaggregate.share;

import com.coopaggregate.error.ErrorCoded;

/** A lot with nothing to share: no kg delivered (LOT_EMPTY) or no sale money (LOT_NO_SALE_MONEY). Answered with 409. */
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

    static LotNotShareableException noSaleMoney(String lotCode) {
        return new LotNotShareableException("LOT_NO_SALE_MONEY",
                "Lot " + lotCode + " has no sale money, so there is nothing to share.");
    }

    @Override
    public String errorCode() {
        return errorCode;
    }
}
