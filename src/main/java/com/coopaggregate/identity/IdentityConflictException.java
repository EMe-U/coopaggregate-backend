package com.coopaggregate.identity;

import com.coopaggregate.error.ErrorCoded;

/** A verification that was already used, or a person who is already a member (409), with its code. */
public class IdentityConflictException extends IllegalStateException implements ErrorCoded {

    private final IdentityErrorCode code;

    public IdentityConflictException(IdentityErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    @Override
    public String errorCode() {
        return code.name();
    }
}
