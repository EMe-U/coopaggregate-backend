package com.coopaggregate.identity;

import com.coopaggregate.error.BadRequestException;

/**
 * The member's details do not match the person who verified. 400. The message names the fields that differ, never
 * their values; the attempt is kept even though the registration is refused.
 */
public class IdentityMismatchException extends BadRequestException {

    public IdentityMismatchException(String message) {
        super(message);
    }
}
