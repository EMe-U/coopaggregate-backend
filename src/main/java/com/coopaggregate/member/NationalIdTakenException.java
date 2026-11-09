package com.coopaggregate.member;

import com.coopaggregate.error.ErrorCoded;

/**
 * Another member already has this national ID (409). The answer names that member's code (M-xxxx) and nothing else
 * about them, and never repeats the ID. memberCode is null when the database refused it before the code was known.
 */
public class NationalIdTakenException extends IllegalStateException implements ErrorCoded {

    private final String memberCode;

    public NationalIdTakenException(String memberCode) {
        super(memberCode == null ? "This national ID is already used by another member."
                : "This national ID is already used by member " + memberCode + ".");
        this.memberCode = memberCode;
    }

    @Override
    public String errorCode() {
        return NationalIdErrorCode.NATIONAL_ID_TAKEN.name();
    }

    @Override
    public String memberCode() {
        return memberCode;
    }
}
