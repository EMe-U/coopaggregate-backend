package com.coopaggregate.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * For development: sends nothing. The message is still saved in sms_log, where it can be read with the code masked.
 * With SMS_LOG_SHOW_CODES=true the real text, code included, is printed in the console so codes can be tested.
 */
@Component
@ConditionalOnProperty(name = "app.sms.provider", havingValue = "log", matchIfMissing = true)
public class LogSmsSender implements SmsSender {

    private static final Logger log = LoggerFactory.getLogger(LogSmsSender.class);

    private final boolean showCodes;

    public LogSmsSender(@Value("${app.sms.log-show-codes:false}") boolean showCodes) {
        this.showCodes = showCodes;
        if (showCodes) {
            log.warn("SMS_LOG_SHOW_CODES is on: one-time codes are printed in the console. Development only.");
        }
    }

    @Override
    public void send(String phone, String message) {
        if (showCodes) {
            log.info("SMS to {} (not sent, development): {}", phone, message);
        }
    }

    @Override
    public String name() {
        return "log";
    }
}
