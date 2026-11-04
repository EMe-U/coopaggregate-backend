package com.coopaggregate.error;

import com.fasterxml.jackson.annotation.JsonInclude;

/** code is only there for errors that have one (identity verification); every other error has the message only. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String message, String code) {

    public ApiError(String message) {
        this(message, null);
    }

    static ApiError of(RuntimeException e) {
        return new ApiError(e.getMessage(), e instanceof ErrorCoded coded ? coded.errorCode() : null);
    }
}
