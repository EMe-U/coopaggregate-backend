package com.coopaggregate.sms;

/** Sends one SMS through a provider. Chosen with the SMS_PROVIDER setting. */
public interface SmsSender {

    /** Sends the message, or throws SmsSendException when the provider did not accept it. */
    void send(String phone, String message);

    /** Short name stored with each message, for example "log" or "africastalking". */
    String name();
}
