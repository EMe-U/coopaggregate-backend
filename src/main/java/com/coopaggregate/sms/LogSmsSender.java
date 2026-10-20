package com.coopaggregate.sms;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** For development: sends nothing. The message is still saved in sms_log, where it can be read. */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "log", matchIfMissing = true)
public class LogSmsSender implements SmsSender {

    @Override
    public void send(String phone, String message) {
        // Nothing to do: SmsService has already saved the message in sms_log.
    }

    @Override
    public String name() {
        return "log";
    }
}
