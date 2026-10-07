package com.coopaggregate.auth;

public class TooManyLoginAttemptsException extends RuntimeException {

    public TooManyLoginAttemptsException() {
        super("Too many failed login attempts. Please wait 5 minutes and try again.");
    }
}
