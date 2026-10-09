package com.coopaggregate.error;

/** The request body refers to something that cannot be used, for example an inactive member. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
