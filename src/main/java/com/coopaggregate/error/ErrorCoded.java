package com.coopaggregate.error;

/** An error that also carries a fixed code, so the client can choose its own message without reading the text. */
public interface ErrorCoded {

    String errorCode();

    /** The member code (M-xxxx) the error is about, for the few errors that name one; otherwise null. */
    default String memberCode() {
        return null;
    }
}
