package com.coopaggregate.delivery;

import com.coopaggregate.error.BadRequestException;

/** A wrong PIN. The failed attempt is still saved, so this must not roll back the transaction. */
public class WrongPinException extends BadRequestException {

    public WrongPinException(String message) {
        super(message);
    }
}
