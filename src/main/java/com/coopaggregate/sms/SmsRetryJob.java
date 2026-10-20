package com.coopaggregate.sms;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Sends failed and stuck messages again, every 5 minutes by default. */
@Component
public class SmsRetryJob {

    private final SmsService smsService;

    public SmsRetryJob(SmsService smsService) {
        this.smsService = smsService;
    }

    @Scheduled(fixedDelayString = "${app.sms.retry-delay-ms:300000}",
            initialDelayString = "${app.sms.retry-delay-ms:300000}")
    public void retry() {
        smsService.findRetryableIds().forEach(smsService::trySend);
    }
}
