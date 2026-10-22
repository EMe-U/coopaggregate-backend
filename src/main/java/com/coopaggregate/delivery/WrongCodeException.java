package com.coopaggregate.delivery;

import com.coopaggregate.error.BadRequestException;

/** A wrong confirmation code. The wrong try is still saved, so this must not roll back the transaction. */
public class WrongCodeException extends BadRequestException {

    public WrongCodeException(String message) {
        super(message);
    }
}
