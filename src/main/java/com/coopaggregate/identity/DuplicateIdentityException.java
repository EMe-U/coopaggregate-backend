package com.coopaggregate.identity;

/** The verified person is already a member. 409, without saying which member. */
public class DuplicateIdentityException extends IllegalStateException {

    public DuplicateIdentityException() {
        super("This person is already a member.");
    }
}
