package com.coopaggregate.identity;

import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.error.ErrorCoded;

/** A verification that cannot be used (400), with its code. The message never holds the person's details. */
public class IdentityRequestException extends BadRequestException implements ErrorCoded {

    private final IdentityErrorCode code;

    public IdentityRequestException(IdentityErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String errorCode() {
        return code.name();
    }
}
