package com.coopaggregate.identity;

/**
 * How the provider checked that the person is who they say they are. PASSWORD is eSignet's password login (acr
 * mosip:idp:acr:password). MOCK was the development stand-in and is only kept for rows that already say so.
 */
public enum VerificationMethod {
    OTP,
    FACE,
    PASSWORD,
    MOCK
}
