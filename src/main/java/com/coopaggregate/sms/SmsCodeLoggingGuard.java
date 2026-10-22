package com.coopaggregate.sms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stops the app from starting when codes would be printed while real SMS are sent. */
@Component
public class SmsCodeLoggingGuard {

    public SmsCodeLoggingGuard(@Value("${app.sms.provider:log}") String provider,
                               @Value("${app.sms.log-show-codes:false}") boolean showCodes) {
        if (showCodes && !"log".equalsIgnoreCase(provider.trim())) {
            throw new IllegalStateException("SMS_LOG_SHOW_CODES=true is only allowed with SMS_PROVIDER=log, "
                    + "but SMS_PROVIDER is '" + provider + "'. It prints one-time codes in the console and is for "
                    + "development only. Remove it or set it to false.");
        }
    }
}
