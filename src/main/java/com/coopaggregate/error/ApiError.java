package com.coopaggregate.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * code is only there for errors that have one (identity verification, national ID); every other error has the message
 * only. memberCode is only there for an error about another member (NATIONAL_ID_TAKEN).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String message, String code, String memberCode) {

    public ApiError(String message) {
        this(message, null, null);
    }

    public ApiError(String message, String code) {
        this(message, code, null);
    }

    static ApiError of(RuntimeException e) {
        return e instanceof ErrorCoded coded
                ? new ApiError(e.getMessage(), coded.errorCode(), coded.memberCode())
                : new ApiError(e.getMessage());
    }
}
