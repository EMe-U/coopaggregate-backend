package com.coopaggregate.identity;

/** The verified person is already a member. 409, without saying which member. */
public class DuplicateIdentityException extends IdentityConflictException {

    public DuplicateIdentityException() {
        super(IdentityErrorCode.ALREADY_MEMBER, "This person is already a member.");
    }
}
