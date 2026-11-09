package com.coopaggregate.member;

import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.error.ErrorCoded;

/** A national ID that is missing or not 16 digits (400). The message never repeats the value. */
public class NationalIdException extends BadRequestException implements ErrorCoded {

    private final NationalIdErrorCode code;

    NationalIdException(NationalIdErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String errorCode() {
        return code.name();
    }
}
